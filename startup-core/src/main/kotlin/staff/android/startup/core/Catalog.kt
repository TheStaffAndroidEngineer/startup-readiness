package staff.android.startup.core

import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class Product(val id: String, val title: String, val price: String, val imageKey: String, val description: String)

object Catalog {
    val featured: List<Product> = listOf(
        Product("camera", "Camera", "₹12,999", "camera", "Keep the moments that matter. A compact camera with a familiar feel and room for your next story."),
        Product("tea", "Tea", "₹450", "tea", "A quiet ritual for your everyday. Carefully selected leaves with a bright and balanced character."),
        Product("bag", "Day bag", "₹2,490", "bag", "Carry the useful things. A durable everyday bag with comfortable handles and room for a little more."),
        Product("lamp", "Reading lamp", "₹1,990", "lamp", "A softer light for slower evenings. Make a comfortable corner for the stories you want to finish."),
        Product("mug", "Everyday mug", "₹690", "mug", "Your morning companion. A generous ceramic mug with a comfortable handle and a reassuring weight."),
        Product("headphones", "Headphones", "₹5,990", "headphones", "A little space for your sound. Comfortable listening for the commute, the desk and the walk home.")
    ).also(::validateFeatured)

    fun validateFeatured(products: List<Product>) {
        require(products.size == 6)
        require(products.map { it.id }.toSet().size == products.size)
        require(products.all { it.id.isNotBlank() && it.title.isNotBlank() && it.price.isNotBlank() && it.imageKey.isNotBlank() })
    }

    /** Immutable for one process. Generated variants provide a controlled local full-catalog fixture. */
    fun searchCatalog(): List<Product> = (1..800).flatMap { edition ->
        featured.map { product -> product.copy(id="${product.id}-$edition", title="${product.title} · Edition $edition") }
    }
}

/** Exact intersection of case-insensitive token prefixes; blank input intentionally returns no results. */
class SearchIndex private constructor(private val products: List<Product>, private val postings: Map<String, Set<Int>>) {
    fun search(query: String): List<Product> {
        val terms = tokens(query).distinct()
        if (terms.isEmpty()) return emptyList()
        val matches = terms.map { postings[it] ?: emptySet() }.reduce { left, right -> left.intersect(right) }
        return matches.sorted().map { products[it] }
    }
    companion object {
        private val separator = Regex("[^\\p{L}\\p{N}]+")
        private fun tokens(text: String) = text.lowercase(Locale.ROOT).split(separator).filter { it.isNotEmpty() }
        fun build(source: List<Product>): SearchIndex {
            val products = source.toList()
            require(products.map { it.id }.toSet().size == products.size) { "Duplicate product ID" }
            val mutable = linkedMapOf<String, MutableSet<Int>>()
            products.forEachIndexed { index, product ->
                tokens(product.title + " " + product.description).distinct().forEach { token ->
                    for (length in 1..token.length) mutable.getOrPut(token.take(length)) { linkedSetOf() }.add(index)
                }
            }
            return SearchIndex(products, mutable.mapValues { it.value.toSet() })
        }
    }
}

/** One process-wide owner, serialized construction, retry after failure, caller cancellation preserved. */
class SearchStore(private val build: suspend () -> SearchIndex) {
    private val mutex = Mutex()
    @Volatile private var index: SearchIndex? = null
    @Volatile var successfulBuilds: Int = 0
        private set
    val isPrepared: Boolean get() = index != null

    suspend fun prepare(): SearchIndex = withContext(Dispatchers.Default) {
        mutex.withLock {
            index ?: build().also {
                currentCoroutineContext().ensureActive()
                index = it
                successfulBuilds++
            }
        }
    }
    suspend fun search(query: String): List<Product> =
        if (query.isBlank()) emptyList() else withContext(Dispatchers.Default) { prepare().search(query) }
}
