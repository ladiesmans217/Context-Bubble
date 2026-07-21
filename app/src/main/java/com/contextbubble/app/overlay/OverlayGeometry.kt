package com.contextbubble.app.overlay

import kotlin.math.max

data class OverlayInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

data class OverlayBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)

    fun clampX(x: Int, objectWidth: Int): Int = x.coerceIn(left, max(left, right - objectWidth))
    fun clampY(y: Int, objectHeight: Int): Int = y.coerceIn(top, max(top, bottom - objectHeight))

    fun snapX(x: Int, objectWidth: Int): Int =
        if (x + objectWidth / 2 < left + width / 2) left else max(left, right - objectWidth)
}

fun calculateOverlayBounds(
    display: OverlayBounds,
    stable: OverlayInsets,
    gestures: OverlayInsets,
    ime: OverlayInsets,
    horizontalGuard: Int,
    verticalGuard: Int,
): OverlayBounds {
    val left = display.left + max(stable.left, gestures.left) + horizontalGuard
    val top = display.top + stable.top + verticalGuard
    val right = display.right - max(stable.right, gestures.right) - horizontalGuard
    val bottom = display.bottom - max(max(stable.bottom, gestures.bottom), ime.bottom) - verticalGuard
    return OverlayBounds(left, top, max(left + 1, right), max(top + 1, bottom))
}

/**
 * Edge-docked controls may sit inside the lateral back-gesture region because simple taps still
 * reach them. Stable system bars and cutouts remain protected, and the full vertical gesture/IME
 * protection is retained. This avoids visually double-counting a side gesture inset as margin.
 */
fun calculateEdgeDockedBounds(
    display: OverlayBounds,
    stable: OverlayInsets,
    gestures: OverlayInsets,
    ime: OverlayInsets,
    horizontalGuard: Int,
    verticalGuard: Int,
): OverlayBounds = calculateOverlayBounds(
    display = display,
    stable = stable,
    gestures = gestures.copy(left = 0, right = 0),
    ime = ime,
    horizontalGuard = horizontalGuard,
    verticalGuard = verticalGuard,
)
