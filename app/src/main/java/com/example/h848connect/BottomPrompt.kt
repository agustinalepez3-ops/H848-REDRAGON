package com.example.h848connect

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Ventana que sube desde abajo, dibujada sobre cualquier app (necesita el permiso
 * "Mostrar sobre otras apps"). Se cierra con "Ahora no", deslizándola hacia abajo
 * o sola a los 25 segundos.
 */
class BottomPrompt(
    context: Context,
    private val deviceName: String,
    private val onConnect: () -> Unit,
    private val onDismissed: () -> Unit,
) {
    private val ctx = ContextThemeWrapper(context.applicationContext, R.style.Theme_H848Connect)
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private val autoClose = Runnable { close(notify = true) }

    /** Devuelve false si no se pudo mostrar (por ejemplo, sin permiso de superposición). */
    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show(): Boolean {
        if (root != null) return true
        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay_prompt, null)
        v.findViewById<TextView>(R.id.promptTitle).text = ctx.getString(R.string.prompt_title, deviceName)
        v.findViewById<TextView>(R.id.promptText).text = ctx.getString(R.string.prompt_text)
        v.findViewById<View>(R.id.btnYes).setOnClickListener {
            close(notify = false)
            onConnect()
        }
        v.findViewById<View>(R.id.btnNo).setOnClickListener { close(notify = true) }

        var downY = 0f
        val threshold = 80 * ctx.resources.displayMetrics.density
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    view.translationY = (e.rawY - downY).coerceAtLeast(0f)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (view.translationY > threshold) {
                        close(notify = true)
                    } else {
                        view.animate().translationY(0f).setDuration(150).start()
                    }
                    true
                }
                else -> false
            }
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.BOTTOM

        v.translationY = 3000f
        return try {
            wm.addView(v, lp)
            root = v
            v.post { v.animate().translationY(0f).setDuration(260).start() }
            handler.postDelayed(autoClose, 25_000)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Cierra sin avisar que se descartó (se usa cuando ya se conectaron). */
    fun dismiss() = close(notify = false)

    private fun close(notify: Boolean) {
        val v = root ?: return
        root = null
        handler.removeCallbacks(autoClose)
        v.animate()
            .translationY(v.height.toFloat() + 200f)
            .setDuration(200)
            .withEndAction {
                try {
                    wm.removeView(v)
                } catch (e: Exception) {
                    // ya estaba removida
                }
            }
            .start()
        if (notify) onDismissed()
    }
}
