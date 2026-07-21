package com.contextbubble.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {
    @Test fun `status cutout gestures and keyboard all contribute to safe bounds`() {
        val result = calculateOverlayBounds(
            display = OverlayBounds(0, 0, 1080, 2400),
            stable = OverlayInsets(0, 96, 0, 120),
            gestures = OverlayInsets(24, 0, 24, 64),
            ime = OverlayInsets(0, 0, 0, 900),
            horizontalGuard = 16,
            verticalGuard = 32,
        )
        assertEquals(40, result.left)
        assertEquals(128, result.top)
        assertEquals(1040, result.right)
        assertEquals(1468, result.bottom)
    }

    @Test fun `bubble clamps inside every edge`() {
        val bounds = OverlayBounds(20, 100, 1060, 2200)
        assertEquals(20, bounds.clampX(-500, 64))
        assertEquals(996, bounds.clampX(5_000, 64))
        assertEquals(100, bounds.clampY(-1, 64))
        assertEquals(2136, bounds.clampY(5_000, 64))
    }

    @Test fun `oversized menu remains anchored without invalid ranges`() {
        val bounds = OverlayBounds(30, 80, 230, 200)
        assertEquals(30, bounds.clampX(500, 400))
        assertEquals(80, bounds.clampY(500, 400))
    }

    @Test fun `snap selects nearest usable edge`() {
        val bounds = OverlayBounds(20, 100, 1060, 2200)
        assertEquals(20, bounds.snapX(100, 64))
        assertEquals(996, bounds.snapX(900, 64))
    }

    @Test fun `edge dock avoids double counting lateral back gesture inset`() {
        val result = calculateEdgeDockedBounds(
            display = OverlayBounds(0, 0, 1080, 2400),
            stable = OverlayInsets(0, 96, 0, 120),
            gestures = OverlayInsets(24, 0, 24, 64),
            ime = OverlayInsets(0, 0, 0, 900),
            horizontalGuard = 10,
            verticalGuard = 8,
        )

        assertEquals(10, result.left)
        assertEquals(1070, result.right)
        assertEquals(104, result.top)
        assertEquals(1492, result.bottom)
    }

    @Test fun `pathological insets still return a nonempty safe rectangle`() {
        val result = calculateOverlayBounds(
            OverlayBounds(0, 0, 100, 100),
            OverlayInsets(80, 80, 80, 80),
            OverlayInsets(80, 80, 80, 80),
            OverlayInsets(0, 0, 0, 90),
            8,
            8,
        )
        assertTrue(result.width > 0)
        assertTrue(result.height > 0)
    }
}
