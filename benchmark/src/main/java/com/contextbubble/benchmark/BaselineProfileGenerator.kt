package com.contextbubble.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(
        packageName = "com.contextbubble.app.play.debug",
        includeInStartupProfile = true,
        filterPredicate = { rule -> rule.contains("Lcom/contextbubble/app/") },
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Settings")), 5_000)
        device.findObject(By.text("Settings"))?.click()
        device.waitForIdle()
        device.findObject(By.scrollable(true))?.fling(androidx.test.uiautomator.Direction.DOWN)
        device.waitForIdle()
        device.findObject(By.text("Vault"))?.click()
        device.waitForIdle()
        device.findObject(By.text("Quick Fill"))?.click()
        device.waitForIdle()
    }
}
