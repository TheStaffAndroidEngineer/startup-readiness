package staff.android.startup

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.awaitCancellation
import staff.android.startup.core.*

@Composable fun BookWindow(content: @Composable () -> Unit) {
    CommonTheme {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
                Box(Modifier.widthIn(max=960.dp).fillMaxWidth().semantics { testTagsAsResourceId=true }) { content() }
            }
        }
    }
}

@Composable fun Brand(action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_common_goods),null,Modifier.size(40.dp))
        Spacer(Modifier.width(10.dp))
        Text("Common Goods",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
        TextButton(onClick=onAction) { Text(action) }
    }
}

@Composable fun ProductPhoto(product: Product, modifier: Modifier, behavior: HeroBehavior = HeroBehavior.Live,
    onReady: () -> Unit = {}, onRetry: (() -> Unit)? = null) {
    val context=LocalContext.current
    val store=remember(context.applicationContext) { ProductImages.get(context) }
    val readyCallback by rememberUpdatedState(onReady)
    key(product.imageKey, behavior, store) {
        var complete by remember { mutableStateOf(false) }
        var accepted by remember { mutableStateOf(false) }
        val bitmap by produceState<android.graphics.Bitmap?>(null) {
            if(behavior==HeroBehavior.Hold) awaitCancellation()
            value=if(behavior==HeroBehavior.Fail) null else store.load(product.imageKey)
            complete=true
        }
        Box(modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center) {
            if(bitmap!=null) {
                Image(bitmap!!.asImageBitmap(),product.title,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                SideEffect {
                    if (!accepted) { accepted=true; readyCallback() }
                }
            }
            else Column(Modifier.padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Text(if(complete) "Photo unavailable" else "Getting your collection ready…",style=MaterialTheme.typography.bodyMedium)
                if(complete && onRetry!=null) TextButton(onClick=onRetry) { Text("Retry photo") }
            }
        }
    }
}

@Composable fun ShopScreen(ready: Boolean, heroBehavior: HeroBehavior, onHeroReady: ()->Unit, onRetry: ()->Unit,
    onSearch: ()->Unit, onProduct: (Product)->Unit, onLab: ()->Unit) {
    val configuration=LocalConfiguration.current
    val columns=if(configuration.fontScale>=1.5f) 1 else if(configuration.screenWidthDp>=700) 3 else 2
    LazyVerticalGrid(GridCells.Fixed(columns),Modifier.fillMaxSize().testTag(if(ready) "shop_ready" else "shop_pending"),
        contentPadding=PaddingValues(bottom=24.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item(span={GridItemSpan(maxLineSpan)}) { Brand("Lab",onLab) }
        item(span={GridItemSpan(maxLineSpan)}) {
            Column(Modifier.padding(horizontal=20.dp)) {
                Text("Good things,\nevery day.",style=MaterialTheme.typography.displaySmall)
                Text("A considered collection for everyday living.",style=MaterialTheme.typography.bodyLarge,modifier=Modifier.padding(top=10.dp))
                OutlinedButton(onClick=onSearch,modifier=Modifier.fillMaxWidth().padding(top=20.dp).heightIn(min=52.dp)) { Text("Search the collection") }
            }
        }
        items(Catalog.featured,key={it.id}) { product ->
            Column(Modifier.padding(horizontal=12.dp)) {
                ProductPhoto(product,Modifier.fillMaxWidth().aspectRatio(1f),
                    behavior=if(product.id=="camera") heroBehavior else HeroBehavior.Live,
                    onReady=if(product.id=="camera") onHeroReady else ({}),
                    onRetry=if(product.id=="camera") onRetry else null)
                Text(product.title,style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=12.dp))
                Text(product.price,style=MaterialTheme.typography.bodyLarge)
                TextButton(onClick={onProduct(product)},contentPadding=PaddingValues(0.dp)) { Text("Explore ${product.title}") }
            }
        }
    }
}

@Composable fun SearchScreen(model: StorefrontViewModel, onBack: ()->Unit) {
    Column(Modifier.fillMaxSize()) {
        Brand("Back",onBack)
        Column(Modifier.padding(horizontal=20.dp)) {
            Text("Find your everyday",style=MaterialTheme.typography.headlineLarge)
            OutlinedTextField(model.query,onValueChange=model::search,label={Text("Search products")},singleLine=true,
                modifier=Modifier.fillMaxWidth().padding(vertical=16.dp).testTag("search_input"))
        }
        when(val state=model.searchState) {
            SearchState.Idle -> Text("Try camera, tea or reading lamp.",Modifier.padding(20.dp))
            is SearchState.Loading -> Column(Modifier.padding(20.dp).testTag("search_loading")) {
                LinearProgressIndicator(Modifier.fillMaxWidth());Text("Finding your everyday…",Modifier.padding(top=16.dp))
            }
            is SearchState.Error -> Column(Modifier.padding(20.dp)) {
                Text("The collection could not be searched.");TextButton(onClick={model.search(model.query)}) {Text("Try again")}
            }
            is SearchState.Results -> {
                Text("${state.products.size} matches",Modifier.padding(horizontal=20.dp).testTag("search_count"))
                if(state.products.isEmpty()) Text("Nothing matched. Try another word.",Modifier.padding(20.dp).testTag("search_empty"))
                LazyColumn(Modifier.testTag("search_results"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    items(state.products.take(60),key={it.id}) { product ->
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            ProductPhoto(product,Modifier.size(88.dp))
                            Column(Modifier.weight(1f).padding(start=16.dp)) {
                                Text(product.title,style=MaterialTheme.typography.titleMedium)
                                Text(product.price)
                                TextButton(onClick={model.selected=product;model.route=Route.Detail}) {Text("View product")}
                            }
                        }
                    }
                    if(state.products.size>60) item { Text("Showing the first 60 matches. Add another word to narrow your search.",style=MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable fun DetailScreen(product: Product, model: StorefrontViewModel, onBack: ()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("detail")) {
        Brand("Back",onBack)
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("THE EVERYDAY COLLECTION",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
            Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) { ProductPhoto(product,Modifier.widthIn(max=504.dp).fillMaxWidth().aspectRatio(1.2f)) }
            Text(product.title,style=MaterialTheme.typography.headlineLarge)
            Text(product.price,style=MaterialTheme.typography.titleLarge)
            Text(product.description,style=MaterialTheme.typography.bodyLarge)
            Text("Dispatches in 2–3 days",style=MaterialTheme.typography.bodyMedium)
            Button(onClick={model.toggleSaved(product.id)},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text(if(product.id in model.saved) "Saved" else "Save for later")}
            HorizontalDivider();Text("Made for your everyday",style=MaterialTheme.typography.titleMedium)
            Text("Thoughtful details. Familiar materials. A useful addition to your day.")
        }
    }
}

@Composable fun StartupLab(mode: String, ready: Boolean, onClose: ()->Unit) {
    AlertDialog(onDismissRequest=onClose,title={Text("Launch investigation")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Launch route: $mode. Shop readiness: ${if(ready) "ready" else "pending"}.")
            Text("Search index prepared: ${ProcessSearch.store.isPrepared}. Successful builds: ${ProcessSearch.store.successfulBuilds}.")
            Text("Six featured products. A separate local search fixture contains 4,800 generated editions. The catalog is fixed for this process.")
            Text("Shop readiness requires its accepted rows and Camera photo. Other collection images can finish independently. Error and diagnostic loading do not report success.")
        }},confirmButton={TextButton(onClick=onClose){Text("Close")}})
}
