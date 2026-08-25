package com.yota.launcher.yota

import android.content.Context
import android.util.Log
import android.view.View
import com.yotadevices.sdk.Epd

/**
 * Minimal low-level wrapper for the Yota EPD SDK. The app compiles against a
 * full-featured stub jar (compileOnly); on a Yota device the real
 * implementation comes from the system shared library com.yotadevices.sdk.
 */
object EInkSdk {

    private const val TAG = "EInkSdk"
    private const val DEBUG = true

    // Animation presets provided by Epd.Animation.
    const val ANIM_OFF = 0
    const val ANIM_HORIZONTAL_LEFT = 1
    const val ANIM_HORIZONTAL_RIGHT = 2
    const val ANIM_HORIZONTAL_OPEN = 3
    const val ANIM_HORIZONTAL_CLOSE = 4
    const val ANIM_VERTICAL_TOP = 5
    const val ANIM_VERTICAL_BOTTOM = 6
    const val ANIM_VERTICAL_OPEN = 7
    const val ANIM_VERTICAL_CLOSE = 8

    /** Returns the int[] frames for one of the ANIM_* presets, or null. */
    fun getAnimationFrames(context: Context, animation: Int): IntArray? {
        if (animation == ANIM_OFF) return null
        return runCatching {
            when (animation) {
                ANIM_HORIZONTAL_LEFT -> Epd.Animation.getAnimationHorizontalLeft(context)
                ANIM_HORIZONTAL_RIGHT -> Epd.Animation.getAnimationHorizontalRight(context)
                ANIM_HORIZONTAL_OPEN -> Epd.Animation.getAnimationHorizontalOpen(context)
                ANIM_HORIZONTAL_CLOSE -> Epd.Animation.getAnimationHorizontalClose(context)
                ANIM_VERTICAL_TOP -> Epd.Animation.getAnimationVerticalTop(context)
                ANIM_VERTICAL_BOTTOM -> Epd.Animation.getAnimationVerticalBottom(context)
                ANIM_VERTICAL_OPEN -> Epd.Animation.getAnimationVerticalOpen(context)
                ANIM_VERTICAL_CLOSE -> Epd.Animation.getAnimationVerticalClose(context)
                else -> null
            }
        }.getOrElse { e ->
            Log.w(TAG, "getAnimationFrames($animation) failed: ${e.message}")
            null
        }.also { frames ->
            if (DEBUG) {
                Log.d(TAG, "getAnimationFrames($animation): " +
                    if (frames == null) "null"
                    else "len=${frames.size} head=${frames.take(8).joinToString(",")}")
            }
        }
    }

    /** Sets the view's EPD update mode via the SDK (0=high quality, 1=high speed, 2=adaptive). */
    fun setUpdateMode(view: View, mode: Int): Boolean {
        val target = view.rootView ?: view
        return runCatching {
            Epd.setUpdateMode(target, mode)
            Log.d(TAG, "setUpdateMode($mode) ok on ${target.javaClass.simpleName}@${System.identityHashCode(target)}")
            true
        }.getOrElse { e ->
            Log.w(TAG, "setUpdateMode($mode) failed: ${e.message}")
            false
        }
    }

    /**
     * Generic animated EPD update. Animated updates are only played by the
     * HAL when the update is a FULL/grayscale-fine update, so we always arm
     * updateType=4 + dithering=1 + customAnimation. Callers must decide
     * whether the current refresh main mode should play animations at all
     * (the app only animates in high-quality mode so the animation follows
     * the selected main mode).
     */
    fun applyPageTurn(view: View, animation: Int): Boolean {
        if (animation == ANIM_OFF) {
            Log.d(TAG, "applyPageTurn: ANIM_OFF, skip")
            return false
        }
        val frames = getAnimationFrames(view.context, animation) ?: return false
        val target = view.rootView ?: view
        if (DEBUG) {
            Log.d(TAG, "applyPageTurn($animation): " +
                "view=${view.javaClass.simpleName}@${System.identityHashCode(view)}, " +
                "attached=${view.isAttachedToWindow}, " +
                "root=${target.javaClass.simpleName}@${System.identityHashCode(target)}")
        }
        return runCatching {
            val before = Epd.getEpdUpdateParams(target)
            if (DEBUG) {
                Log.d(TAG, "applyPageTurn($animation): before set -> " +
                    "updateType=${before.updateType}, dithering=${before.dithering}, " +
                    "anim=${before.customAnimation?.size ?: -1}")
            }
            before.updateType = 4
            before.dithering = 1
            before.customAnimation = frames
            Epd.setEpdUpdateParams(target, before)
            if (DEBUG) {
                val after = Epd.getEpdUpdateParams(target)
                Log.d(TAG, "applyPageTurn($animation): after set -> " +
                    "updateType=${after.updateType}, dithering=${after.dithering}, " +
                    "anim=${after.customAnimation?.size ?: -1}")
            }
            target.invalidate()
            Log.d(TAG, "applyPageTurn($animation): invalidate root done")
            true
        }.getOrElse { e ->
            Log.w(TAG, "applyPageTurn($animation) failed: ${e.message}")
            false
        }
    }

    /** Screen-on / screen-off animation: same animated update, no direction logic. */
    fun applyScreenAnimation(view: View, animation: Int): Boolean {
        return applyPageTurn(view, animation)
    }

    /**
     * 手动全刷：一次无动画的 FULL 更新（updateType=4 + dithering=1）。
     * 用于用户手动清除残影。
     */
    fun manualFullRefresh(view: View): Boolean {
        val target = view.rootView ?: view
        return runCatching {
            val params = Epd.getEpdUpdateParams(target)
            params.updateType = 4
            params.dithering = 1
            params.customAnimation = null
            Epd.setEpdUpdateParams(target, params)
            target.invalidate()
            Log.d(TAG, "manualFullRefresh done on ${target.javaClass.simpleName}@${System.identityHashCode(target)}")
            true
        }.getOrElse { e ->
            Log.w(TAG, "manualFullRefresh failed: ${e.message}")
            false
        }
    }
}
