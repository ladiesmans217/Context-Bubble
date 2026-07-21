package com.contextbubble.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSearchTest {
    private val apps = listOf(
        LaunchableApp("Chrome", "com.android.chrome"),
        LaunchableApp("Microsoft Teams", "com.microsoft.teams"),
        LaunchableApp("Sticker Maker", "stickermaker.stickercreater.whatsappstickers.sticker"),
        LaunchableApp("WhatsApp", "com.whatsapp"),
    )

    @Test fun `search matches app names without case sensitivity`() {
        assertEquals(listOf(apps[1]), filterLaunchableApps(apps, "tEaMs"))
    }

    @Test fun `search matches package names and trims whitespace`() {
        assertEquals(listOf(apps[0]), filterLaunchableApps(apps, "  android.chrome  "))
    }

    @Test fun `blank search preserves the full ordered list`() {
        assertEquals(apps, filterLaunchableApps(apps, "   "))
    }

    @Test fun `unknown search returns an empty list`() {
        assertEquals(emptyList<LaunchableApp>(), filterLaunchableApps(apps, "spotify"))
    }

    @Test fun `exact visible-name match ranks above package-only match`() {
        assertEquals(listOf(apps[3], apps[2]), filterLaunchableApps(apps, "whatsapp"))
    }
}
