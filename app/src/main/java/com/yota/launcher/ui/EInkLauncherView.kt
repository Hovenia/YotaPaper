package com.yota.launcher.ui

import android.content.Context
import android.content.pm.ResolveInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.yota.launcher.R
import kotlin.math.abs
import kotlin.math.min

/**
 * E-ink app grid, modeled on the reference launcher's grid management:
 *
 * - The ViewGroup always keeps a stable pool of [ItemViewHolder]s sized
 *   `columns * rows`, exactly like the reference `EInkLauncherView` /
 *   `LauncherAdapter` pair. Children are inflated only when the grid size
 *   changes.
 * - Page data is only rebound into the existing holders; empty slots are
 *   cleared in place. No remove/add, no requestLayout, no invalidate is
 *   issued for data changes.
 */
class EInkLauncherView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    interface OnItemInteractionListener {
        fun onItemClick(info: ResolveInfo)
        fun onItemLongClick(info: ResolveInfo)
    }

    interface OnPageChangeListener {
        fun onNextPage()
        fun onPrevPage()
    }

    private class ItemViewHolder(val itemView: View) {
        val icon: ImageView = itemView.findViewById(R.id.icon)
        val label: TextView = itemView.findViewById(R.id.label)
        val marker: TextView = itemView.findViewById(R.id.marker)
        var boundKey: String? = null
    }

    private var columns = 4
    private var rows = 5
    private var apps: List<ResolveInfo?> = emptyList()
    private var selection: Set<String> = emptySet()
    private var hiddenApps: Set<String> = emptySet()
    private var manageMode = false
    private var showDividers = false
    private var itemListener: OnItemInteractionListener? = null
    private var pageListener: OnPageChangeListener? = null

    private val holders = ArrayList<ItemViewHolder>()

    private val dividerPaint = Paint().apply {
        color = 0xFFD5D5D0.toInt()
        strokeWidth = 1f
    }

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var swipeThreshold = 60f

    init {
        // ViewGroup skips onDraw by default; the divider hairlines live there.
        setWillNotDraw(false)
    }

    fun configure(columns: Int, rows: Int) {
        this.columns = columns.coerceIn(2, 6)
        this.rows = rows.coerceIn(1, 7)
    }

    fun setApps(
        pageApps: List<ResolveInfo?>,
        selection: Set<String> = emptySet(),
        manageMode: Boolean = false,
        showDividers: Boolean = false,
        hidden: Set<String> = emptySet()
    ) {
        apps = pageApps
        this.selection = selection
        this.manageMode = manageMode
        this.hiddenApps = hidden
        val dividerChanged = this.showDividers != showDividers
        this.showDividers = showDividers
        resetGrid()
        if (dividerChanged) invalidate()
    }

    /**
     * Updates only selection markers. Does not touch icons, labels or layout,
     * so toggling an app in manage mode stays as cheap as possible.
     */
    fun updateSelection(selection: Set<String>) {
        this.selection = selection
        if (!manageMode) return
        for (i in holders.indices) {
            val info = apps.getOrNull(i) ?: continue
            val marker = holders[i].marker
            if (marker.visibility != View.VISIBLE) continue
            val pkg = info.activityInfo.packageName
            val text = when {
                selection.contains(pkg) -> "●"
                hiddenApps.contains(pkg) -> "隐"
                else -> "○"
            }
            if (marker.text != text) marker.text = text
            val color = if (selection.contains(pkg)) 0xFF1A1A1A.toInt() else 0xFF8A8A8A.toInt()
            if (marker.currentTextColor != color) marker.setTextColor(color)
        }
    }

    fun setOnItemInteractionListener(listener: OnItemInteractionListener?) {
        itemListener = listener
    }

    fun setOnPageChangeListener(listener: OnPageChangeListener?) {
        pageListener = listener
    }

    // ------------------------------------------------------------------ Grid

    /**
     * Reference-style reset: keep a stable holder pool of `columns * rows`.
     * Inflate only when that size changes; otherwise just rebind data.
     */
    private fun resetGrid() {
        val targetCount = columns * rows
        if (holders.size != targetCount) {
            removeAllViews()
            holders.clear()
            val inflater = LayoutInflater.from(context)
            for (i in 0 until targetCount) {
                val itemView = inflater.inflate(R.layout.item_app, this, false)
                itemView.isHapticFeedbackEnabled = false
                addView(itemView)
                holders.add(ItemViewHolder(itemView))
            }
        }
        rebind()
    }

    private fun rebind() {
        for (i in holders.indices) {
            bindHolder(holders[i], apps.getOrNull(i))
        }
    }

    private fun bindHolder(holder: ItemViewHolder, info: ResolveInfo?) {
        val item = holder.itemView
        val icon = holder.icon
        val label = holder.label
        val marker = holder.marker

        if (info == null) {
            // Reference-style clear: empty slot is blank and fully transparent.
            holder.boundKey = null
            if (item.alpha != 0f) item.alpha = 0f
            if (icon.drawable != null) icon.setImageDrawable(null)
            if (icon.alpha != 1f) icon.alpha = 1f
            if (label.text?.isNotEmpty() == true) label.text = ""
            if (marker.visibility != View.GONE) marker.visibility = View.GONE
            if (item.isClickable) item.isClickable = false
            if (item.isLongClickable) item.isLongClickable = false
            item.setOnClickListener(null)
            item.setOnLongClickListener(null)
            return
        }

        if (item.alpha != 1f) item.alpha = 1f

        // 冷启动首帧只绑定 label；图标异步加载，内存/磁盘缓存命中后回填。
        val key = info.activityInfo.packageName + "/" + info.activityInfo.name
        holder.boundKey = key
        IconLoader.loadAsync(context, info) { drawable ->
            if (holder.boundKey == key && drawable != null) {
                icon.setImageDrawable(drawable)
            }
        }

        val labelText = info.loadLabel(context.packageManager)
        if (label.text != labelText) label.text = labelText

        val packageName = info.activityInfo.packageName
        val isHidden = hiddenApps.contains(packageName)
        val desiredAlpha = if (isHidden) 0.35f else 1f
        if (icon.alpha != desiredAlpha) icon.alpha = desiredAlpha

        val desiredLabelColor = if (isHidden) 0xFF8A8A8A.toInt() else 0xFF1A1A1A.toInt()
        if (label.currentTextColor != desiredLabelColor) label.setTextColor(desiredLabelColor)

        if (manageMode) {
            val markerText = when {
                selection.contains(packageName) -> "●"
                isHidden -> "隐"
                else -> "○"
            }
            val markerColor =
                if (selection.contains(packageName)) 0xFF1A1A1A.toInt() else 0xFF8A8A8A.toInt()
            if (marker.visibility != View.VISIBLE) marker.visibility = View.VISIBLE
            if (marker.text != markerText) marker.text = markerText
            if (marker.currentTextColor != markerColor) marker.setTextColor(markerColor)
            item.isClickable = true
            item.isLongClickable = false
            item.setOnClickListener { itemListener?.onItemClick(info) }
            item.setOnLongClickListener(null)
        } else {
            if (marker.visibility != View.GONE) marker.visibility = View.GONE
            item.isClickable = true
            item.isLongClickable = true
            item.setOnClickListener { itemListener?.onItemClick(info) }
            item.setOnLongClickListener {
                itemListener?.onItemLongClick(info)
                true
            }
        }
    }

    // ------------------------------------------------------------------ Layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val w = widthSize - paddingLeft - paddingRight
        val h = heightSize - paddingTop - paddingBottom
        if (w > 0 && h > 0) {
            val cellW = MeasureSpec.makeMeasureSpec(w / columns, MeasureSpec.EXACTLY)
            val cellH = MeasureSpec.makeMeasureSpec(h / rows, MeasureSpec.EXACTLY)
            for (holder in holders) {
                holder.itemView.measure(cellW, cellH)
            }
        }
        setMeasuredDimension(widthSize, heightSize)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return
        swipeThreshold = min(w, h) / 8f
        val cellW = w / columns
        val cellH = h / rows
        for (index in holders.indices) {
            val row = index / columns
            val col = index % columns
            holders[index].itemView.layout(
                paddingLeft + col * cellW,
                paddingTop + row * cellH,
                paddingLeft + (col + 1) * cellW,
                paddingTop + (row + 1) * cellH
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showDividers) return
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return
        val cellW = w / columns
        val cellH = h / rows
        for (col in 1 until columns) {
            val x = (paddingLeft + col * cellW).toFloat()
            canvas.drawLine(x, paddingTop.toFloat(), x, (paddingTop + h).toFloat(), dividerPaint)
        }
        for (row in 1 until rows) {
            val y = (paddingTop + row * cellH).toFloat()
            canvas.drawLine(paddingLeft.toFloat(), y, (paddingLeft + w).toFloat(), y, dividerPaint)
        }
    }

    // ------------------------------------------------------------------ Gesture

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.x
                touchDownY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.x - touchDownX)
                val dy = abs(ev.y - touchDownY)
                if (swipeThreshold > 0 && (dx > swipeThreshold || dy > swipeThreshold)) {
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                val adx = abs(dx)
                val ady = abs(dy)
                if (swipeThreshold > 0 && (adx > swipeThreshold || ady > swipeThreshold)) {
                    val next: Boolean
                    val axis: String
                    if (adx > ady) {
                        next = dx < 0
                        axis = if (dx < 0) "left" else "right"
                    } else {
                        next = dy < 0
                        axis = if (dy < 0) "up" else "down"
                    }
                    android.util.Log.d("EInkLauncherView",
                        "swipe: dx=$dx dy=$dy threshold=$swipeThreshold -> " +
                        "$axis ${if (next) "onNextPage" else "onPrevPage"}")
                    if (next) pageListener?.onNextPage() else pageListener?.onPrevPage()
                    return true
                } else {
                    android.util.Log.d("EInkLauncherView",
                        "swipe ignored: dx=$dx dy=$dy threshold=$swipeThreshold")
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
