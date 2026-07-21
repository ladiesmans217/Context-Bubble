package com.contextbubble.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.contextbubble.app.domain.AssistantAction

class BubbleMenuView(
    context: Context,
    private val onAction: (AssistantAction) -> Unit,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        elevation = dp(14).toFloat()
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(0xF7111827.toInt())
            setStroke(dp(1), 0x30FFFFFF)
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        val actions = listOf(
            AssistantAction.ASK to "Ask about screen",
            AssistantAction.REMEMBER to "Remember",
            AssistantAction.GENERATE_IMAGE to "Generate image",
            AssistantAction.QUICK_FILL to "Quick Fill",
            AssistantAction.REMINDER to "Reminder",
            AssistantAction.PAUSE to "Pause bubble",
        )
        actions.forEach { (action, label) ->
            addView(TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.TRANSPARENT)
                }
                setOnClickListener { onAction(action) }
                isClickable = true
                isFocusable = true
            }, LayoutParams(dp(190), dp(44)))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
