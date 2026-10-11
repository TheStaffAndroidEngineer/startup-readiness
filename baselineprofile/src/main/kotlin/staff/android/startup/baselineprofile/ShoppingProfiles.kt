package staff.android.startup.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class ShoppingProfiles {
    @get:Rule val profile = BaselineProfileRule()
    @Test fun shopStartup() = profile.collect(
        packageName = "staff.android.startup",
        includeInStartupProfile = true,
        maxIterations = 15,
        stableIterations = 3,
        strictStability = true,
    ) {
        pressHome()
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.res("shop_ready")), 15_000))
        device.waitForIdle()
    }
    @Test fun firstSearch() = profile.collect(
        packageName = "staff.android.startup",
        includeInStartupProfile = false,
        maxIterations = 15,
        stableIterations = 3,
        strictStability = true,
    ) {
        pressHome()
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.res("shop_ready")), 15_000))
        device.findObject(By.text("Search the collection")).click()
        check(device.wait(Until.hasObject(By.res("search_input")), 5_000))
        device.findObject(By.res("search_input")).text = "camera"
        check(device.wait(Until.hasObject(By.text("800 matches")), 15_000))
        device.waitForIdle()
    }
}
