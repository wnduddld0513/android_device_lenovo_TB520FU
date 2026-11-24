/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.dap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.RangeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import java.text.NumberFormat
import com.android.settingslib.widget.theme.R as ThemeR

// The graphic equalizer: one vertical track per band, from -6 dB at the bottom to +6 dB at the
// top, set in 0.5 dB steps. Touching a track moves its band to the touch point.
class EqualizerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    interface Listener {
        // A band moved to another step; the array holds every band in dB.
        fun onBandsChanged(bands: FloatArray)

        // The user let go of the band, or an accessibility action set it.
        fun onBandsCommitted(bands: FloatArray)
    }

    var listener: Listener? = null

    private val bands = FloatArray(DolbyCore.GEQ_BANDS)
    private var activeBand = NO_BAND

    private val trackWidth = dp(12f)
    private val handleWidth = dp(24f)
    private val handleHeight = dp(4f)
    private val labelGap = dp(8f)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1f) }
    private val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sp(12f)
            textAlign = Paint.Align.CENTER
        }
    private val valuePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sp(14f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    private val colorTrack = context.getColor(ThemeR.color.settingslib_materialColorSecondaryContainer)
    private val colorActive = context.getColor(ThemeR.color.settingslib_materialColorPrimary)
    private val colorLabel = context.getColor(ThemeR.color.settingslib_materialColorOnSurfaceVariant)
    private val colorZeroLine = context.getColor(ThemeR.color.settingslib_materialColorOutlineVariant)

    private val dbFormat = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }

    // Layout, filled in by onSizeChanged.
    private val columns = Array(DolbyCore.GEQ_BANDS) { RectF() }
    private var axisCenterX = 0f
    private var topY = 0f
    private var zeroY = 0f
    private var bottomY = 0f
    private var valueBaseline = 0f
    private var labelBaseline = 0f

    private val accessHelper = AccessHelper()

    init {
        isFocusable = true
        ViewCompat.setAccessibilityDelegate(this, accessHelper)
    }

    fun setBands(values: FloatArray) {
        for (band in bands.indices) {
            bands[band] = values.getOrElse(band) { 0f }.coerceIn(-MAX_DB, MAX_DB)
        }
        invalidate()
        accessHelper.invalidateRoot()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled && activeBand != NO_BAND) {
            commit()
        }
        invalidate()
        accessHelper.invalidateRoot()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL

        val axisWidth =
            AXIS_LABELS.maxOf { labelPaint.measureText(formatDb(it)) }
                .coerceAtLeast(labelPaint.measureText(HZ)) + labelGap
        val left = paddingStart.toFloat()
        val right = (w - paddingEnd).toFloat()
        axisCenterX = if (rtl) right - axisWidth / 2 else left + axisWidth / 2

        val valueHeight = valuePaint.fontMetrics.let { it.descent - it.ascent }
        val labelHeight = labelPaint.fontMetrics.let { it.descent - it.ascent }
        valueBaseline = paddingTop - valuePaint.fontMetrics.ascent
        topY = paddingTop + valueHeight + labelGap + handleHeight / 2
        labelBaseline = h - paddingBottom - labelPaint.fontMetrics.descent
        bottomY = h - paddingBottom - labelHeight - labelGap - handleHeight / 2
        zeroY = (topY + bottomY) / 2

        val bandsLeft = if (rtl) left else left + axisWidth
        val bandsRight = if (rtl) right - axisWidth else right
        val columnWidth = (bandsRight - bandsLeft) / bands.size
        for (band in bands.indices) {
            val slot = if (rtl) bands.size - 1 - band else band
            val columnLeft = bandsLeft + slot * columnWidth
            columns[band].set(columnLeft, 0f, columnLeft + columnWidth, h.toFloat())
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val alpha = if (isEnabled) OPAQUE else DISABLED_ALPHA
        trackPaint.color = colorTrack
        activePaint.color = colorActive
        zeroLinePaint.color = colorZeroLine
        labelPaint.color = colorLabel
        valuePaint.color = colorActive
        for (paint in arrayOf(trackPaint, activePaint, zeroLinePaint, labelPaint, valuePaint)) {
            paint.alpha = alpha
        }

        val first = columns.minOf { it.left }
        val last = columns.maxOf { it.right }
        canvas.drawLine(first, zeroY, last, zeroY, zeroLinePaint)

        for ((index, db) in AXIS_LABELS.withIndex()) {
            val y = when (index) {
                0 -> topY
                1 -> zeroY
                else -> bottomY
            }
            canvas.drawText(formatDb(db), axisCenterX, y - labelPaint.centerOffset(), labelPaint)
        }
        canvas.drawText(HZ, axisCenterX, labelBaseline, labelPaint)

        val radius = trackWidth / 2
        for (band in bands.indices) {
            val x = columns[band].centerX()
            val y = valueY(bands[band])

            canvas.drawRoundRect(
                x - radius, topY - radius, x + radius, bottomY + radius, radius, radius, trackPaint)
            canvas.drawRoundRect(
                x - radius,
                minOf(y, zeroY) - radius,
                x + radius,
                maxOf(y, zeroY) + radius,
                radius,
                radius,
                activePaint,
            )
            canvas.drawRoundRect(
                x - handleWidth / 2,
                y - handleHeight / 2,
                x + handleWidth / 2,
                y + handleHeight / 2,
                handleHeight / 2,
                handleHeight / 2,
                activePaint,
            )
            canvas.drawText(FREQUENCY_LABELS[band], x, labelBaseline, labelPaint)
        }

        if (activeBand != NO_BAND) {
            canvas.drawText(
                formatDb(bands[activeBand]), columns[activeBand].centerX(), valueBaseline, valuePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val band = bandAt(event.x)
                if (band == NO_BAND) {
                    return false
                }
                activeBand = band
                parent?.requestDisallowInterceptTouchEvent(true)
                moveActiveBand(event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (activeBand != NO_BAND) moveActiveBand(event.y)
            MotionEvent.ACTION_UP -> {
                if (activeBand != NO_BAND) {
                    commit()
                }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> if (activeBand != NO_BAND) commit()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        accessHelper.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        accessHelper.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    private fun moveActiveBand(y: Float) {
        val db = (zeroY - y) / (zeroY - topY) * MAX_DB
        setBand(activeBand, db)
    }

    // Moves a band to the step nearest to db and reports it when the step changed.
    private fun setBand(band: Int, db: Float): Boolean {
        val snapped = DolbyCore.snapGeqDb(db)
        if (snapped == bands[band]) {
            return false
        }
        bands[band] = snapped
        listener?.onBandsChanged(bands.copyOf())
        invalidate()
        accessHelper.invalidateVirtualView(band)
        return true
    }

    private fun commit() {
        val band = activeBand
        activeBand = NO_BAND
        parent?.requestDisallowInterceptTouchEvent(false)
        listener?.onBandsCommitted(bands.copyOf())
        invalidate()
        accessHelper.invalidateVirtualView(band)
    }

    private fun bandAt(x: Float): Int = columns.indexOfFirst { x >= it.left && x <= it.right }

    private fun valueY(db: Float): Float = zeroY - db / MAX_DB * (zeroY - topY)

    private fun formatDb(db: Float): String =
        (if (db > 0f) "+" else "") + dbFormat.format(db.toDouble()) + " dB"

    private fun Paint.centerOffset(): Float = (fontMetrics.ascent + fontMetrics.descent) / 2

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    // Each band is a virtual seek bar for TalkBack and switch access.
    private inner class AccessHelper : ExploreByTouchHelper(this@EqualizerView) {
        override fun getVirtualViewAt(x: Float, y: Float): Int =
            bandAt(x).takeIf { it != NO_BAND } ?: INVALID_ID

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            bands.indices.forEach { virtualViewIds.add(it) }
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat,
        ) {
            val db = bands[virtualViewId]
            node.className = SeekBar::class.java.name
            node.contentDescription = "${FREQUENCIES_HZ[virtualViewId]} Hz"
            node.stateDescription = formatDb(db)
            node.rangeInfo =
                RangeInfoCompat.obtain(RangeInfoCompat.RANGE_TYPE_FLOAT, -MAX_DB, MAX_DB, db)
            val column = columns[virtualViewId]
            @Suppress("DEPRECATION")
            node.setBoundsInParent(
                Rect(
                    column.left.toInt(),
                    column.top.toInt(),
                    column.right.toInt(),
                    column.bottom.toInt(),
                )
            )
            node.isEnabled = isEnabled
            if (isEnabled) {
                if (db < MAX_DB) node.addAction(AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
                if (db > -MAX_DB) node.addAction(AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
                node.addAction(AccessibilityActionCompat.ACTION_SET_PROGRESS)
            }
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?,
        ): Boolean {
            if (!isEnabled) {
                return false
            }
            val current = bands[virtualViewId]
            val target =
                when (action) {
                    AccessibilityActionCompat.ACTION_SCROLL_FORWARD.id -> current + STEP_DB
                    AccessibilityActionCompat.ACTION_SCROLL_BACKWARD.id -> current - STEP_DB
                    AccessibilityActionCompat.ACTION_SET_PROGRESS.id ->
                        arguments?.getFloat(
                            AccessibilityNodeInfoCompat.ACTION_ARGUMENT_PROGRESS_VALUE
                        ) ?: return false
                    else -> return false
                }
            if (!setBand(virtualViewId, target)) {
                return false
            }
            listener?.onBandsCommitted(bands.copyOf())
            return true
        }
    }

    private companion object {
        const val NO_BAND = -1
        const val OPAQUE = 255
        const val DISABLED_ALPHA = 97 // 38 %
        const val MAX_DB = DolbyCore.GEQ_MAX_DB
        const val STEP_DB = DolbyCore.GEQ_STEP_DB
        const val HZ = "Hz"

        val AXIS_LABELS = floatArrayOf(MAX_DB, 0f, -MAX_DB)

        // Centre frequencies of the even gains of dax-default.xml, the bands on screen.
        val FREQUENCIES_HZ = intArrayOf(47, 234, 469, 844, 1313, 2250, 3750, 5813, 9000, 13875)
        val FREQUENCY_LABELS =
            arrayOf("47", "234", "469", "844", "1.3k", "2.3k", "3.8k", "5.8k", "9k", "14k")
    }
}
