package staff.android.startup

import android.os.Bundle
import android.os.Trace
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import staff.android.startup.core.*

object ProcessSearch {
    private val failNext = java.util.concurrent.atomic.AtomicBoolean(false)
    var store = newStore()
        private set
    private fun newStore() = SearchStore {
        Trace.beginSection("CG23/buildSearchIndex")
        try {
            check(!failNext.getAndSet(false)) { "Controlled local index failure" }
            SearchIndex.build(Catalog.searchCatalog())
        }
        finally { Trace.endSection() }
    }
    fun failNextBuildForDebug() { check(BuildConfig.DEBUG); failNext.set(true) }
    fun resetForTest() { check(BuildConfig.DEBUG); failNext.set(false); store = newStore() }
}

enum class Route { Shop, Search, Detail }
enum class HeroBehavior { Live, Hold, Fail }
sealed interface SearchState {
    data object Idle : SearchState
    data class Loading(val query: String) : SearchState
    data class Results(val query: String, val products: List<Product>) : SearchState
    data class Error(val query: String) : SearchState
}

class StorefrontViewModel : ViewModel() {
    var route by mutableStateOf(Route.Shop)
    var selected by mutableStateOf<Product?>(null)
    var saved by mutableStateOf(emptySet<String>())
        private set
    var query by mutableStateOf("")
        private set
    var searchState by mutableStateOf<SearchState>(SearchState.Idle)
        private set
    private var searchJob: Job? = null
    fun toggleSaved(id: String) { saved = if (id in saved) saved - id else saved + id }
    fun search(text: String) {
        query = text
        searchJob?.cancel()
        if (text.isBlank()) { searchState = SearchState.Idle; return }
        searchState = SearchState.Loading(text)
        searchJob = viewModelScope.launch {
            try {
                val products = ProcessSearch.store.search(text)
                searchState = SearchState.Results(text, products)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { searchState = SearchState.Error(text) }
        }
    }
}

class MainActivity : ComponentActivity() {
    private var usefulShopReady by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = if (intent.getStringExtra("mode") == "eager") "eager" else "deferred"
        if (BuildConfig.DEBUG && savedInstanceState == null && intent.getBooleanExtra("searchFailure", false)) {
            ProcessSearch.failNextBuildForDebug()
        }
        if (mode == "eager") {
            // Deliberately broken dependency: blocks launch while a worker prepares optional Search.
            Trace.beginSection("CG23/awaitEagerIndex")
            try { runBlocking { ProcessSearch.store.prepare() } }
            finally { Trace.endSection() }
        }
        val model = ViewModelProvider(this)[StorefrontViewModel::class.java]
        fullyDrawnReporter.addOnReportDrawnListener { Log.i("CG23", "FULLY_DRAWN mode=$mode") }
        setContent {
            var heroBehavior by remember {
                mutableStateOf(if (BuildConfig.DEBUG) when(intent.getStringExtra("hero")) {
                    "hold" -> HeroBehavior.Hold
                    "fail" -> HeroBehavior.Fail
                    else -> HeroBehavior.Live
                } else HeroBehavior.Live)
            }
            var showLab by remember { mutableStateOf(false) }
            ReportDrawnWhen { usefulShopReady }
            BackHandler(enabled = model.route != Route.Shop) { model.route = Route.Shop }
            BookWindow {
                when(model.route) {
                    Route.Shop -> ShopScreen(
                        ready = usefulShopReady,
                        heroBehavior = heroBehavior,
                        onHeroReady = { usefulShopReady = true },
                        onRetry = { heroBehavior = HeroBehavior.Live },
                        onSearch = { model.route = Route.Search },
                        onProduct = { model.selected = it; model.route = Route.Detail },
                        onLab = { showLab = true },
                    )
                    Route.Search -> SearchScreen(model, onBack = { model.route = Route.Shop })
                    Route.Detail -> DetailScreen(model.selected ?: Catalog.featured.first(), model, onBack = { model.route = Route.Shop })
                }
                if(showLab) StartupLab(mode, usefulShopReady, onClose = { showLab = false })
            }
        }
    }
}
