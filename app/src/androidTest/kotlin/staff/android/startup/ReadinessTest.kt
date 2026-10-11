package staff.android.startup

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadinessTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun launch(hero: String = "live", failSearch: Boolean = false, mode: String = "deferred") =
        ActivityScenario.launch<MainActivity>(Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("hero", hero).putExtra("searchFailure", failSearch).putExtra("mode", mode))
    private fun ready() { compose.waitUntil(15_000) { compose.onAllNodesWithTag("shop_ready").fetchSemanticsNodes().isNotEmpty() } }
    private fun matches() { compose.waitUntil(15_000) { compose.onAllNodesWithText("800 matches").fetchSemanticsNodes().isNotEmpty() } }
    @Before fun reset() = main { ProcessSearch.resetForTest() }

    @Test fun usefulShopDoesNotBuildSearch() {
        launch().use { scenario ->
            ready(); assertFalse(ProcessSearch.store.isPrepared)
            scenario.onActivity { assertTrue(it.fullyDrawnReporter.isFullyDrawnReported) }
        }
    }
    @Test fun heldPhotoDoesNotClaimReadiness() {
        launch(hero="hold").use { scenario ->
            compose.onNodeWithTag("shop_pending").assertExists()
            scenario.onActivity { assertFalse(it.fullyDrawnReporter.isFullyDrawnReported) }
        }
    }
    @Test fun failedPhotoWaitsUntilSuccessfulRetry() {
        launch(hero="fail").use { scenario ->
            compose.onNodeWithText("Retry photo").assertExists()
            scenario.onActivity { assertFalse(it.fullyDrawnReporter.isFullyDrawnReported) }
            compose.onNodeWithText("Retry photo").performClick(); ready()
            scenario.onActivity { assertTrue(it.fullyDrawnReporter.isFullyDrawnReported) }
        }
    }
    @Test fun firstSearchBuildsOnceAndSurvivesRecreation() {
        launch().use { scenario ->
            ready(); compose.onNodeWithText("Search the collection").performClick()
            compose.onNodeWithTag("search_input").performTextInput("camera"); matches()
            assertEquals(1,ProcessSearch.store.successfulBuilds)
            scenario.recreate(); matches()
            compose.onNodeWithTag("search_input").performTextReplacement("zzzzunmatched")
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("search_empty").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1,ProcessSearch.store.successfulBuilds)
        }
    }
    @Test fun failedFirstSearchCanRetryWithoutPublishingFailure() {
        launch(failSearch=true).use {
            ready(); compose.onNodeWithText("Search the collection").performClick()
            compose.onNodeWithTag("search_input").performTextInput("camera")
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty() }
            assertFalse(ProcessSearch.store.isPrepared); assertEquals(0,ProcessSearch.store.successfulBuilds)
            compose.onNodeWithText("Try again").performClick(); matches()
            assertEquals(1,ProcessSearch.store.successfulBuilds)
        }
    }
    @Test fun eagerComparisonStillShowsSameShopAndProductSave() {
        launch(mode="eager").use {
            ready(); assertEquals(1,ProcessSearch.store.successfulBuilds)
            compose.onNodeWithText("Explore Camera").performScrollTo().performClick()
            compose.onNodeWithText("Save for later").performScrollTo().performClick()
            compose.onNodeWithText("Saved").assertExists()
        }
    }
}
