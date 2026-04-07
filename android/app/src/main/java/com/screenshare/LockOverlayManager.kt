package com.screenshare

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Manages a fullscreen [WindowManager] overlay that locks the broadcaster's own touch input
 * while the viewer/controller holds the lock.
 *
 * **Behaviour:**
 *  - The overlay is **fully transparent** — the broadcaster can see their screen at all times.
 *  - The overlay does **not** carry [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE], so it
 *    absorbs any touch events the broadcaster tries to generate (they cannot tap through).
 *  - Gestures injected by the viewer via [RestrictedAppsAccessibilityService.dispatchGesture]
 *    are delivered at the input-subsystem level and bypass the view hierarchy entirely, so the
 *    viewer retains full remote control even while the overlay is active.
 *  - A small floating badge at the bottom of the screen lets the broadcaster know they are
 *    locked without obscuring the content they can see.
 *
 * Requires [android.Manifest.permission.SYSTEM_ALERT_WINDOW].
 */
class LockOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    val isShowing: Boolean get() = overlayView != null

    /**
     * Shows the transparent lock overlay.
     *
     * @param message Text shown in the bottom badge
     *                (e.g. "🔒 Input locked by your controller").
     */
    fun show(message: String = context.getString(R.string.label_lock_overlay)) {
        if (isShowing) return
        if (!Settings.canDrawOverlays(context)) return

        // Transparent fullscreen container — absorbs broadcaster touches but lets the
        // broadcaster see everything underneath.
        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Small non-obstructive badge pinned to the bottom centre.
        val badge = TextView(context).apply {
            text = message
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt()) // 80 % opaque black pill
            gravity = Gravity.CENTER
            val px = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(px * 2, px, px * 2, px)
        }
        val badgeParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            val marginPx = (100 * context.resources.displayMetrics.density).toInt()
            bottomMargin = marginPx
        }
        container.addView(badge, badgeParams)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // No FLAG_NOT_TOUCHABLE: this overlay WILL intercept the broadcaster's own
            // touch events so they cannot interact with whatever is running underneath.
            // Viewer gestures (dispatchGesture) are injected below the view hierarchy and
            // are unaffected by this flag.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(container, params)
            overlayView = container
        } catch (e: Exception) {
            overlayView = null
        }
    }

    fun hide() {
        val v = overlayView ?: return
        try {
            windowManager.removeView(v)
        } catch (_: Exception) {}
        overlayView = null
    }

    fun toggle() {
        if (isShowing) hide() else show()
    }
}
