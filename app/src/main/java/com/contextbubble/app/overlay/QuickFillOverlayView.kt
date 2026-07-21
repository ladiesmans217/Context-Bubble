package com.contextbubble.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.contextbubble.app.data.QuickFillItem

class QuickFillOverlayView(
    context: Context,
    items: List<QuickFillItem>,
    onSelected: (QuickFillItem) -> Unit,
) : ScrollView(context) {
    init {
        elevation = dp(14).toFloat()
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(0xF7111827.toInt())
            setStroke(dp(1), 0x30FFFFFF)
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            if (items.isEmpty()) {
                addView(row("No Quick Fill items yet", "Open the app to add one"))
            } else {
                items.forEach { item ->
                    addView(row(item.label, item.kind).apply { setOnClickListener { onSelected(item) } })
                }
            }
        })
    }

    private fun row(title: String, subtitle: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(8))
        addView(TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 1
        })
        addView(TextView(context).apply {
            text = subtitle
            textSize = 11f
            setTextColor(0xFFAEB5C3.toInt())
            maxLines = 1
        })
        isClickable = true
        isFocusable = true
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
