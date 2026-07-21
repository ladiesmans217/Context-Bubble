package com.contextbubble.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

class BubbleView(context: Context) : View(context) {
    var listener: Listener? = null
    var state: VisualState = VisualState.IDLE
        set(value) {
            field = value
            invalidate()
        }

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bounds = RectF()
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false
    private var longPressed = false

    private val longPress = Runnable {
        if (!dragging && isPressed) {
            longPressed = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            state = VisualState.LISTENING
            listener?.onHoldStarted()
        }
    }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Context Bubble. Tap for actions, hold to speak, drag to move."
        minimumWidth = dp(64)
        minimumHeight = dp(64)
        elevation = dp(8).toFloat()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(64), widthMeasureSpec), resolveSize(dp(64), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val accent = when (state) {
            VisualState.IDLE -> COBALT
            VisualState.LISTENING -> CYAN
            VisualState.BLOCKED, VisualState.WARNING -> CORAL
            VisualState.WORKING -> MINT
        }
        canvas.drawCircle(cx, cy, size * 0.37f, corePaint)
        ringPaint.color = accent
        ringPaint.strokeWidth = size * 0.075f
        canvas.drawCircle(cx, cy, size * 0.285f, ringPaint)
        ringPaint.strokeWidth = size * 0.035f
        bounds.set(cx - size * 0.43f, cy - size * 0.43f, cx + size * 0.43f, cy + size * 0.43f)
        val arcStart = if (state == VisualState.WORKING) {
            ((SystemClock.uptimeMillis() % WORKING_ROTATION_MS).toFloat() / WORKING_ROTATION_MS * 360f) - 90f
        } else {
            -55f
        }
        canvas.drawArc(bounds, arcStart, when (state) {
            VisualState.LISTENING -> 305f
            VisualState.WORKING -> 105f
            else -> 135f
        }, false, ringPaint)
        canvas.drawCircle(cx, cy, size * 0.075f, dotPaint)
        // Animate only while work is active; idle retains zero animation cost.
        if (state == VisualState.WORKING) postInvalidateOnAnimation()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        // Reserve only the bubble itself, not an entire screen edge, so drag gestures remain
        // reliable while the rest of the edge continues to behave as Android's Back gesture.
        systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                downRawX = event.rawX
                downRawY = event.rawY
                dragging = false
                longPressed = false
                handler.postDelayed(longPress, HOLD_DELAY_MS)
                listener?.onTouchPrepared()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!longPressed && !dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                    handler.removeCallbacks(longPress)
                    listener?.onDragStarted()
                }
                if (dragging) listener?.onDragged(event.rawX, event.rawY)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                isPressed = false
                when {
                    dragging -> listener?.onDragFinished(event.rawX, event.rawY)
                    longPressed -> {
                        state = VisualState.WORKING
                        listener?.onHoldFinished(event.actionMasked == MotionEvent.ACTION_CANCEL)
                    }
                    event.actionMasked == MotionEvent.ACTION_UP -> {
                        performClick()
                        listener?.onTapped()
                    }
                }
                dragging = false
                longPressed = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }

    fun interface SimpleCallback { fun invoke() }

    interface Listener {
        fun onTouchPrepared()
        fun onTapped()
        fun onDragStarted()
        fun onDragged(rawX: Float, rawY: Float)
        fun onDragFinished(rawX: Float, rawY: Float)
        fun onHoldStarted()
        fun onHoldFinished(cancelled: Boolean)
    }

    enum class VisualState { IDLE, LISTENING, WORKING, WARNING, BLOCKED }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val HOLD_DELAY_MS = 300L
        const val WORKING_ROTATION_MS = 900L
        const val INK = 0xFF111827.toInt()
        const val COBALT = 0xFF5C64F4.toInt()
        const val CYAN = 0xFF62D4E7.toInt()
        const val CORAL = 0xFFFF7369.toInt()
        const val MINT = 0xFF62D5A5.toInt()
    }
}
