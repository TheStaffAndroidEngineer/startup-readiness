package staff.android.startup.core

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SearchContractTest {
    private fun product(id: String, title: String, description: String = "") =
        Product(id, title, "₹100", "camera", description)
    private val products = listOf(
        product("travel-camera", "Camera", "Compact travel companion"),
        product("studio-camera", "Camera", "For the studio"),
        product("travel-bag", "Bag", "Travel companion"),
    )

    @Test fun tokenPrefixesIntersectAcrossTitleAndDescriptionWithoutDuplicateResults() {
        val index = SearchIndex.build(products)
        assertEquals(listOf("travel-camera"), index.search("CAM tra CAM").map { it.id })
        assertEquals(listOf("travel-camera", "travel-bag"), index.search("COMPAN").map { it.id })
    }

    @Test fun unknownTermsAndNonPrefixesDoNotBecomeMatches() {
        val index = SearchIndex.build(products)
        assertTrue(index.search("amera").isEmpty())
        assertTrue(index.search("camera telescope").isEmpty())
        assertTrue(index.search("   ").isEmpty())
        assertTrue(index.search("---").isEmpty())
    }

    @Test fun resultsPreserveCatalogOrderRatherThanSortingProductIds() {
        val source = listOf(product("z", "Camera"), product("a", "Camera"), product("m", "Camera"))
        assertEquals(listOf("z", "a", "m"), SearchIndex.build(source).search("cam").map { it.id })
    }

    @Test fun indexDetachesFromLaterCallerListEdits() {
        val source = products.toMutableList()
        val index = SearchIndex.build(source)
        source.clear()
        source += product("new", "Camera")
        assertEquals(listOf("travel-camera", "studio-camera"), index.search("camera").map { it.id })
    }

    @Test fun duplicateProductIdentityIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SearchIndex.build(listOf(product("same", "Camera"), product("same", "Bag")))
        }
    }

    @Test fun featuredContractRequiresSixValidDistinctRows() {
        Catalog.validateFeatured(Catalog.featured)
        assertThrows(IllegalArgumentException::class.java) { Catalog.validateFeatured(Catalog.featured.dropLast(1)) }
        assertThrows(IllegalArgumentException::class.java) {
            Catalog.validateFeatured(Catalog.featured.dropLast(1) + Catalog.featured.first())
        }
        assertThrows(IllegalArgumentException::class.java) {
            Catalog.validateFeatured(Catalog.featured.mapIndexed { i, p -> if (i == 0) p.copy(price = " ") else p })
        }
    }

    @Test fun blankSearchDoesNotPrepareAnUnusedIndex() = runBlocking {
        val calls = AtomicInteger()
        val store = SearchStore { calls.incrementAndGet(); SearchIndex.build(products) }
        assertTrue(store.search(" \n\t ").isEmpty())
        assertEquals(0, calls.get())
        assertEquals(0, store.successfulBuilds)
        assertFalse(store.isPrepared)
    }

    @Test fun overlappingCallersPublishAndReuseExactlyOneIndex() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val expected = SearchIndex.build(products)
            val store = SearchStore {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                expected
            }
            val first = async { store.prepare() }
            entered.await()
            val others = List(12) { async { store.prepare() } }
            assertFalse(store.isPrepared)
            release.complete(Unit)
            val results = listOf(first).plus(others).awaitAll()
            results.forEach { assertSame(expected, it) }
            assertEquals(1, calls.get())
            assertEquals(1, store.successfulBuilds)
            assertTrue(store.isPrepared)
            assertEquals(listOf("travel-camera"), store.search("camera travel").map { it.id })
            assertEquals(1, calls.get())
        }
    }

    @Test fun failedConstructionPublishesNothingAndNextCallCanRetry() = runBlocking {
        withTimeout(5_000) {
            val calls = AtomicInteger()
            val store = SearchStore {
                if (calls.incrementAndGet() == 1) throw IllegalStateException("controlled construction failure")
                SearchIndex.build(products)
            }
            val first = runCatching { store.prepare() }
            assertInstanceOf(IllegalStateException::class.java, first.exceptionOrNull())
            assertFalse(store.isPrepared)
            assertEquals(0, store.successfulBuilds)
            assertEquals(2, store.search("camera").size)
            assertTrue(store.isPrepared)
            assertEquals(2, calls.get())
            assertEquals(1, store.successfulBuilds)
        }
    }

    @Test fun cancellationBeforePublicationDiscardsEvenACompletedFactoryResult() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val expected = SearchIndex.build(products)
            val store = SearchStore {
                if (calls.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    // Model a factory that finishes CPU work despite caller cancellation.
                    withContext(NonCancellable) { release.await() }
                }
                expected
            }
            val first = async { store.prepare() }
            entered.await()
            first.cancel()
            release.complete(Unit)
            first.join()
            assertTrue(first.isCancelled)
            assertFalse(store.isPrepared)
            assertEquals(0, store.successfulBuilds)
            assertSame(expected, store.prepare())
            assertEquals(2, calls.get())
            assertEquals(1, store.successfulBuilds)
        }
    }

    @Test fun cancellingAWaitingCallerDoesNotCancelAnotherCallersConstruction() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val expected = SearchIndex.build(products)
            val store = SearchStore {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                expected
            }
            val owner = async { store.prepare() }
            entered.await()
            val waiting = async { store.prepare() }
            waiting.cancelAndJoin()
            assertTrue(owner.isActive)
            release.complete(Unit)
            assertSame(expected, owner.await())
            assertSame(expected, store.prepare())
            assertEquals(1, calls.get())
            assertEquals(1, store.successfulBuilds)
        }
    }
}
