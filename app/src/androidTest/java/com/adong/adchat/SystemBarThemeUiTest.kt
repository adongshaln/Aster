package com.adong.adchat

import android.os.SystemClock
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adong.adchat.data.THEME_MODE_ASTER
import com.adong.adchat.data.THEME_MODE_DARK
import com.adong.adchat.ui.MainViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** App theme overrides must update both system bars, independently of the OS theme. */
@RunWith(AndroidJUnit4::class)
class SystemBarThemeUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun bothSystemBarsFollowTheSelectedAppTheme() {
        var original = THEME_MODE_ASTER
        activity.scenario.onActivity {
            val vm = ViewModelProvider(it)[MainViewModel::class.java]
            original = vm.appConfig.themeMode
            vm.skipUserNamePrompt()
        }
        try {
            activity.scenario.onActivity { ViewModelProvider(it)[MainViewModel::class.java].setThemeMode(THEME_MODE_DARK) }
            assertTrue("Both system bars must use light icons for the app's dark theme", awaitBarAppearance(false))
            activity.scenario.onActivity { ViewModelProvider(it)[MainViewModel::class.java].setThemeMode(THEME_MODE_ASTER) }
            assertTrue("Both system bars must use dark icons for the app's light theme", awaitBarAppearance(true))
        } finally {
            activity.scenario.onActivity { ViewModelProvider(it)[MainViewModel::class.java].setThemeMode(original) }
        }
    }

    private fun awaitBarAppearance(light: Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var matches = false
            activity.scenario.onActivity {
                val controller = WindowCompat.getInsetsController(it.window, it.window.decorView)
                matches = controller.isAppearanceLightStatusBars == light &&
                    controller.isAppearanceLightNavigationBars == light
            }
            if (matches) return true
            SystemClock.sleep(50)
        }
        return false
    }
}
