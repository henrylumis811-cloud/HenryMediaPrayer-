package com.henrylumis.mediaprayer.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.henrylumis.mediaprayer.R

/** Small, consistent HUD popup used for quick choices such as sorting and library view. */
object GlassPopup {
    data class Item(val label: String, val checked: Boolean = false)

    fun show(context: Context, anchor: View, items: List<Item>, onClick: (Int) -> Unit) {
        val density = context.resources.displayMetrics.density
        val pad = (14 * density).toInt()
        val rowHeight = (48 * density).toInt()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            background = CarGlassDrawable(context.resources)
        }

        items.forEachIndexed { index, item ->
            val row = TextView(context).apply {
                text = if (item.checked) "✓  ${item.label}" else item.label
                textSize = 14f
                setTextColor(if (item.checked) Color.rgb(0, 229, 255) else Color.rgb(242, 246, 250))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, 0, pad, 0)
                minHeight = rowHeight
                isSingleLine = true
                background = context.getDrawable(R.drawable.bg_pill_outline)
                setOnClickListener {
                    onClick(index)
                }
            }
            panel.addView(row, LinearLayout.LayoutParams(-1, rowHeight).apply {
                if (index > 0) topMargin = (4 * density).toInt()
            })
        }

        val popup = PopupWindow(panel, (210 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = 18 * density
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            setOnDismissListener { panel.removeAllViews() }
        }
        popup.showAsDropDown(anchor, -(popup.width - anchor.width), (4 * density).toInt())
    }
}
