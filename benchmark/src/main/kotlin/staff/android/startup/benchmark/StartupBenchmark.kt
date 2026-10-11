package staff.android.startup.benchmark

import android.content.Intent
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    @Test fun eagerColdFull() = launch("eager", StartupMode.COLD, CompilationMode.Full())
    @Test fun deferredColdFull() = launch("deferred", StartupMode.COLD, CompilationMode.Full())
    @Test fun deferredColdNone() = launch("deferred", StartupMode.COLD, CompilationMode.None())
    @Test fun deferredColdProfile() = launch("deferred", StartupMode.COLD,
        CompilationMode.Partial(BaselineProfileMode.Require, warmupIterations = 0))
    @Test fun deferredWarmFull() = launch("deferred", StartupMode.WARM, CompilationMode.Full())
    @Test fun deferredHotFull() = launch("deferred", StartupMode.HOT, CompilationMode.Full())
    @OptIn(ExperimentalMetricApi::class)
    @Test fun firstSearchDeferred() = benchmark.measureRepeated(
        packageName = "staff.android.startup",
        metrics = listOf(TraceSectionMetric("CG23/buildSearchIndex", TraceSectionMetric.Mode.Sum, label = "indexBuild")),
        compilationMode = CompilationMode.Full(),
        iterations = 3,
        setupBlock = {
            killProcess()
            startActivityAndWait { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("mode", "deferred")
            }
            check(device.wait(Until.hasObject(By.res("shop_ready")), 15_000))
            device.findObject(By.text("Search the collection")).click()
            check(device.wait(Until.hasObject(By.res("search_input")), 5_000))
        },
    ) {
        device.findObject(By.res("search_input")).text = "camera"
        check(device.wait(Until.hasObject(By.text("800 matches")), 15_000))
        device.waitForIdle()
        println("CG23_SEARCH query=camera matches=800 firstUse=true")
    }

    private fun launch(mode: String, startup: StartupMode, compilation: CompilationMode) = benchmark.measureRepeated(
        packageName = "staff.android.startup",
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilation,
        startupMode = startup,
        iterations = 3,
        setupBlock = { pressHome() },
    ) {
        // Macrobenchmark supplies the correct launch flags for each startup mode.
        startActivityAndWait { intent -> intent.putExtra("mode", mode) }
        check(device.wait(Until.hasObject(By.res("shop_ready")), 15_000)) { "Useful Shop endpoint was not reached" }
        device.waitForIdle()
        println("CG23_READY route=$mode startup=$startup compilation=$compilation")
    }
}
