package com.contextbubble.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundPackageResolverTest {
    private val ownPackage = "com.contextbubble.app.play.debug"

    @Test fun `active application root wins over overlay event package`() {
        assertEquals(
            "com.whatsapp",
            resolveForegroundPackage(
                activeRootPackage = "com.whatsapp",
                eventPackage = ownPackage,
                previousPackage = "com.whatsapp",
                ownPackage = ownPackage,
            ),
        )
    }

    @Test fun `own overlay event cannot replace previous app while root is unavailable`() {
        assertEquals(
            "com.android.chrome",
            resolveForegroundPackage(
                activeRootPackage = null,
                eventPackage = ownPackage,
                previousPackage = "com.android.chrome",
                ownPackage = ownPackage,
            ),
        )
    }

    @Test fun `real foreground transition is accepted`() {
        assertEquals(
            "com.google.android.apps.nbu.paisa.user",
            resolveForegroundPackage(
                activeRootPackage = "com.google.android.apps.nbu.paisa.user",
                eventPackage = "com.google.android.apps.nbu.paisa.user",
                previousPackage = "com.whatsapp",
                ownPackage = ownPackage,
            ),
        )
    }

    @Test fun `focusable editor overlay keeps the underlying package`() {
        assertEquals(
            "com.whatsapp",
            resolveForegroundPackage(
                activeRootPackage = ownPackage,
                eventPackage = ownPackage,
                previousPackage = "com.whatsapp",
                ownPackage = ownPackage,
                ownOverlayHasFocus = true,
            ),
        )
    }
}
