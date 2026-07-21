package com.contextbubble.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun coldStartup() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 8,
        setupBlock = { pressHome() },
    ) { startActivityAndWait() }

    @Test fun warmStartup() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 8,
        setupBlock = { pressHome() },
    ) { startActivityAndWait() }

    @Test fun settingsCloudAndVaultJourney() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 8,
        setupBlock = { startActivityAndWait() },
    ) {
        device.wait(Until.hasObject(By.text("Settings")), 5_000)
        device.findObject(By.text("Settings"))?.click()
        device.findObject(By.scrollable(true))?.fling(androidx.test.uiautomator.Direction.DOWN)
        device.findObject(By.text("Vault"))?.click()
        device.findObject(By.text("Quick Fill"))?.click()
        device.waitForIdle()
    }

    private companion object { const val PACKAGE = "com.contextbubble.app.play.debug" }
}
