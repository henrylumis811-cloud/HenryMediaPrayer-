package com.henrylumis.mediaprayer.ui.common

import android.app.Dialog
import android.graphics.Color
import android.view.ViewGroup
import android.widget.TextView

/** Applies the Henry Media Player HUD/glass treatment to all standard dialogs. */
object DialogStyler {
    fun style(dialog: Dialog) {
        val window = dialog.window ?: return
        window.setBackgroundDrawable(CarGlassDrawable(dialog.context.resources))
        window.setDimAmount(0.62f)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        window.setLayout(
            (dialog.context.resources.displayMetrics.widthPixels * 0.90f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        tintButtons(dialog)
    }

    fun show(dialog: Dialog): Dialog {
        dialog.show()
        style(dialog)
        return dialog
    }


    fun show(builder: androidx.appcompat.app.AlertDialog.Builder): androidx.appcompat.app.AlertDialog {
        val dialog = builder.create()
        show(dialog)
        return dialog
    }

    fun show(builder: android.app.AlertDialog.Builder): android.app.AlertDialog {
        val dialog = builder.create()
        show(dialog)
        return dialog
    }

    private fun tintButtons(dialog: Dialog) {
        val cyan = android.graphics.Color.rgb(0, 229, 255)
        dialog.findViewById<TextView>(android.R.id.button1)?.setTextColor(cyan)
        dialog.findViewById<TextView>(android.R.id.button2)?.setTextColor(cyan)
        dialog.findViewById<TextView>(android.R.id.button3)?.setTextColor(cyan)
    }
}
