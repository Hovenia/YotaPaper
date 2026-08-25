package com.yota.launcher.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Generates e-ink line-art icons from an original app icon, following the
 * palma launcher strategy:
 *
 *  - simple icons (few colors, mostly light): Otsu threshold + dilation
 *  - complex icons: weighted blur + Sobel + edge hysteresis
 *  - if the line result is too dense, fall back to a high-contrast grayscale
 *    version of the original inside a rounded-rect border
 */
object LineIconRenderer {

    private const val PROC = 192

    fun fromDrawable(source: Drawable, sizePx: Int = 176, resources: Resources = Resources.getSystem()): Drawable? {
        val bitmap = when (source) {
            is BitmapDrawable -> source.bitmap
            else -> {
                val b = Bitmap.createBitmap(PROC, PROC, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(b)
                canvas.drawColor(Color.WHITE)
                source.setBounds(0, 0, PROC, PROC)
                source.draw(canvas)
                b
            }
        }
        return fromBitmap(bitmap, sizePx, resources)
    }

    fun fromBitmap(source: Bitmap, sizePx: Int = 176, resources: Resources = Resources.getSystem()): Drawable? {
        if (source.width <= 0 || source.height <= 0) return null
        val normalized = Bitmap.createScaledBitmap(source, PROC, PROC, true)

        val pixels = IntArray(PROC * PROC)
        normalized.getPixels(pixels, 0, PROC, 0, 0, PROC, PROC)
        val gray = IntArray(PROC * PROC) { i ->
            val a = Color.alpha(pixels[i])
            if (a < 10) 255 else (0.299 * Color.red(pixels[i]) + 0.587 * Color.green(pixels[i]) + 0.114 * Color.blue(pixels[i])).toInt()
        }

        val simple = isSimpleLineIcon(pixels)
        val contentPixels = if (simple) processSimpleIcon(gray, pixels) else processComplexIcon(gray)

        // Count black ratio over the icon's non-transparent area.
        var nonTransparent = 0
        var black = 0
        for (i in contentPixels.indices) {
            if (Color.alpha(pixels[i]) < 10) continue
            nonTransparent++
            if (contentPixels[i] == Color.BLACK) black++
        }

        val displaySize = sizePx
        val borderWidth = maxOf(2f, displaySize * 0.04f)
        val cornerRadius = displaySize * 0.22f
        val cornerRadiusInner = maxOf(0f, cornerRadius - borderWidth)

        // Fallback: too dense to be line art -> grayscale enhanced original in a border.
        if (nonTransparent > 0 && black.toDouble() / nonTransparent > 0.55) {
            val output = Bitmap.createBitmap(displaySize, displaySize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val ds = displaySize.toFloat()
            val outer = roundedRectPath(0f, 0f, ds, ds, cornerRadius)
            val inner = roundedRectPath(borderWidth, borderWidth, ds - borderWidth, ds - borderWidth, cornerRadiusInner)

            canvas.drawPath(outer, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL })
            canvas.drawPath(inner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL })

            val optimized = optimizedGrayscale(BitmapDrawable(resources, normalized))
            val inset = borderWidth + maxOf(1f, borderWidth * 0.4f)
            canvas.save()
            canvas.clipPath(inner)
            optimized.setBounds(inset.toInt(), inset.toInt(), (ds - inset).toInt(), (ds - inset).toInt())
            optimized.draw(canvas)
            canvas.restore()
            if (normalized !== source) normalized.recycle()
            return BitmapDrawable(resources, output)
        }

        // Erase line pixels that would touch the rounded border.
        for (y in 0 until PROC) {
            for (x in 0 until PROC) {
                val idx = y * PROC + x
                if (contentPixels[idx] == Color.BLACK && distToRoundedRect(x, y) < PROC * 0.06f) {
                    contentPixels[idx] = Color.WHITE
                }
            }
        }

        val contentBitmap = Bitmap.createBitmap(contentPixels, PROC, PROC, Bitmap.Config.ARGB_8888)
        val scaledContent = Bitmap.createScaledBitmap(contentBitmap, displaySize, displaySize, true)

        val output = Bitmap.createBitmap(displaySize, displaySize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val ds = displaySize.toFloat()
        val outer = roundedRectPath(0f, 0f, ds, ds, cornerRadius)
        val inner = roundedRectPath(borderWidth, borderWidth, ds - borderWidth, ds - borderWidth, cornerRadiusInner)

        canvas.drawPath(outer, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL })
        canvas.drawPath(inner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL })

        canvas.save()
        canvas.clipPath(inner)
        val inset = borderWidth + maxOf(1f, borderWidth * 0.4f)
        canvas.drawBitmap(
            scaledContent, null,
            RectF(inset, inset, ds - inset, ds - inset),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        canvas.restore()

        if (normalized !== source) normalized.recycle()
        contentBitmap.recycle()
        scaledContent.recycle()
        return BitmapDrawable(resources, output)
    }

    /** Fallback: thin circle outline + first character of the label. */
    fun draw(label: String, sizePx: Int = 176, resources: Resources = Resources.getSystem()): Drawable? {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val stroke = sizePx / 22f
        val center = sizePx / 2f
        val radius = sizePx / 2f - stroke * 2f

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            this.strokeWidth = stroke
        }
        canvas.drawCircle(center, center, radius, linePaint)

        val char = label.trim().firstOrNull()?.toString() ?: "?"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.42f
        }
        val baseline = center - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(char, center, baseline, textPaint)

        return BitmapDrawable(resources, bitmap)
    }

    // ---- classification -------------------------------------------------

    private fun isSimpleLineIcon(pixels: IntArray): Boolean {
        val colorSet = mutableSetOf<Int>()
        var lightCount = 0
        var totalCount = 0
        for (p in pixels) {
            val a = Color.alpha(p)
            if (a < 10) continue
            totalCount++
            colorSet.add(Color.rgb((Color.red(p) / 64) * 64, (Color.green(p) / 64) * 64, (Color.blue(p) / 64) * 64))
            if ((0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).toInt() > 200) lightCount++
        }
        return colorSet.size <= 5 && (if (totalCount > 0) lightCount.toDouble() / totalCount else 0.0) > 0.50
    }

    // ---- simple path ----------------------------------------------------

    private fun processSimpleIcon(gray: IntArray, originalPixels: IntArray): IntArray {
        val histogram = IntArray(256)
        for (g in gray) histogram[g]++

        var sumAll = 0L
        for (i in 0 until 256) sumAll += i.toLong() * histogram[i]
        var sumB = 0L
        var wB = 0
        var maxVariance = 0.0
        var bestThreshold = 128
        val total = gray.size
        for (t in 0 until 256) {
            wB += histogram[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * histogram[t]
            val mB = sumB.toDouble() / wB
            val mF = (sumAll - sumB).toDouble() / wF
            val v = wB.toLong() * wF.toLong() * (mB - mF) * (mB - mF)
            if (v > maxVariance) {
                maxVariance = v
                bestThreshold = t
            }
        }

        val result = IntArray(total) { Color.WHITE }
        var nonTransparent = 0
        var black = 0
        for (i in gray.indices) {
            if (Color.alpha(originalPixels[i]) < 10) continue
            nonTransparent++
            if (gray[i] < bestThreshold) {
                result[i] = Color.BLACK
                black++
            }
        }
        // Otsu collapsed: too much black means this is not a simple icon.
        if (nonTransparent > 0 && black.toDouble() / nonTransparent > 0.65) return processComplexIcon(gray)

        // Thicken thin lines for the e-ink panel.
        val thickened = result.copyOf()
        for (y in 1 until 191) {
            for (x in 1 until 191) {
                val idx = y * PROC + x
                if (thickened[idx] != Color.WHITE) continue
                var hit = false
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (result[(y + dy) * PROC + (x + dx)] == Color.BLACK) {
                            hit = true
                            break
                        }
                    }
                    if (hit) break
                }
                if (hit) thickened[idx] = Color.BLACK
            }
        }
        return thickened
    }

    // ---- complex path ---------------------------------------------------

    private fun processComplexIcon(gray: IntArray): IntArray {
        val smoothed = IntArray(gray.size)
        for (y in 0 until PROC) {
            for (x in 0 until PROC) {
                var sum = 0
                var weight = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val ny = (y + dy).coerceIn(0, PROC - 1)
                        val nx = (x + dx).coerceIn(0, PROC - 1)
                        val w = when (abs(dx) + abs(dy)) { 0 -> 4; 1 -> 2; else -> 1 }
                        sum += gray[ny * PROC + nx] * w
                        weight += w
                    }
                }
                smoothed[y * PROC + x] = sum / weight
            }
        }

        val sobelMag = IntArray(PROC * PROC)
        var maxMag = 0
        for (y in 1 until PROC - 1) {
            for (x in 1 until PROC - 1) {
                val tl = smoothed[(y - 1) * PROC + (x - 1)]
                val tc = smoothed[(y - 1) * PROC + x]
                val tr = smoothed[(y - 1) * PROC + (x + 1)]
                val ml = smoothed[y * PROC + (x - 1)]
                val mr = smoothed[y * PROC + (x + 1)]
                val bl = smoothed[(y + 1) * PROC + (x - 1)]
                val bc = smoothed[(y + 1) * PROC + x]
                val br = smoothed[(y + 1) * PROC + (x + 1)]
                val m = abs(-tl + tr - 2 * ml + 2 * mr - bl + br) + abs(-tl - 2 * tc - tr + bl + 2 * bc + br)
                sobelMag[y * PROC + x] = m
                if (m > maxMag) maxMag = m
            }
        }
        if (maxMag == 0) return IntArray(PROC * PROC) { Color.WHITE }

        // Fixed thresholds derived from the observed magnitude range, then
        // scaled so icons with soft edges still produce a usable line map.
        val scale = (240f / 1020f).coerceAtLeast(maxMag / 1020f)
        val strong = (150 * scale).toInt().coerceAtLeast(40)
        val weak = (60 * scale).toInt().coerceAtLeast(18)

        val edgeMap = IntArray(PROC * PROC) { i -> when { sobelMag[i] >= strong -> 2; sobelMag[i] >= weak -> 1; else -> 0 } }
        val finalEdge = IntArray(PROC * PROC) { Color.WHITE }
        for (i in edgeMap.indices) if (edgeMap[i] == 2) finalEdge[i] = Color.BLACK

        var changed = true
        while (changed) {
            changed = false
            for (y in 1 until PROC - 1) {
                for (x in 1 until PROC - 1) {
                    val idx = y * PROC + x
                    if (edgeMap[idx] != 1 || finalEdge[idx] == Color.BLACK) continue
                    var found = false
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (finalEdge[(y + dy) * PROC + (x + dx)] == Color.BLACK) {
                                found = true
                                break
                            }
                        }
                        if (found) break
                    }
                    if (found) {
                        finalEdge[idx] = Color.BLACK
                        changed = true
                    }
                }
            }
        }

        var blackCount = 0
        for (p in finalEdge) if (p == Color.BLACK) blackCount++
        if (blackCount.toDouble() / (PROC * PROC) > 0.50) {
            // Retry with stricter thresholds: keep only the strongest edges.
            val strong2 = (250 * scale).toInt().coerceAtLeast(80)
            val weak2 = (120 * scale).toInt().coerceAtLeast(40)
            val retry = IntArray(PROC * PROC) { Color.WHITE }
            for (i in sobelMag.indices) if (sobelMag[i] >= strong2) retry[i] = Color.BLACK
            var rc = true
            while (rc) {
                rc = false
                for (y in 1 until PROC - 1) {
                    for (x in 1 until PROC - 1) {
                        val idx = y * PROC + x
                        if (sobelMag[idx] !in weak2 until strong2 || retry[idx] == Color.BLACK) continue
                        var f = false
                        for (dy in -1..1) {
                            for (dx in -1..1) {
                                if (retry[(y + dy) * PROC + (x + dx)] == Color.BLACK) {
                                    f = true
                                    break
                                }
                            }
                            if (f) break
                        }
                        if (f) {
                            retry[idx] = Color.BLACK
                            rc = true
                        }
                    }
                }
            }
            return retry
        }
        return finalEdge
    }

    // ---- helpers --------------------------------------------------------

    private fun distToRoundedRect(px: Int, py: Int): Float {
        val x = px.toFloat()
        val y = py.toFloat()
        val s = PROC.toFloat()
        val r = PROC * 0.22f
        val ds = minOf(x, s - x, y, s - y)
        val inL = x < r
        val inR = x > s - r
        val inT = y < r
        val inB = y > s - r
        if ((inL || inR) && (inT || inB)) {
            val cx = if (inL) r else s - r
            val cy = if (inT) r else s - r
            return r - sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
        }
        return ds
    }

    private fun roundedRectPath(l: Float, t: Float, r: Float, b: Float, radius: Float): Path =
        Path().apply { addRoundRect(RectF(l, t, r, b), radius, radius, Path.Direction.CW) }

    /** Grayscale + contrast boost, used when line art is too dense. */
    private fun optimizedGrayscale(source: Drawable): Drawable {
        val mutated = source.constantState?.newDrawable()?.mutate() ?: source.mutate()
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    1.3f, 0f, 0f, 0f, -10f,
                    0f, 1.3f, 0f, 0f, -10f,
                    0f, 0f, 1.3f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        mutated.colorFilter = ColorMatrixColorFilter(matrix)
        return mutated
    }
}
