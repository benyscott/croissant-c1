package com.bleural.croissant.ui.beam

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.bleural.croissant.Cell

// The mobile signal as four bars rising left to right, 6 wide and 4 apart, 7 to 22 tall on one
// foot: 36 x 22 in page px. Only drawn while a SIM is in use ([Cell] is null otherwise), on Home's
// top right and the control centre's (2026-11-17). No battery or clock beside it: kept clean.
internal const val BARS_W = 36f
internal const val BARS_H = 22f
private const val BAR_W = 6f
private const val BAR_GAP = 4f
private val BAR_HEIGHTS = floatArrayOf(7f, 12f, 17f, 22f)

/**
 * The bars with their top left at ([x], [y]), [u] device px per page px. Lit to [Cell.bars] in
 * [on] while data is carrying, in [sub] while it is not (no data, or switched off); the rest in [off].
 */
internal fun DrawScope.cellBars(cell: Cell, x: Float, y: Float, u: Float, on: Color, sub: Color, off: Color, alpha: Float = 1f) {
    val lit = if (cell.dataUp) on else sub
    BAR_HEIGHTS.forEachIndexed { i, h ->
        drawRoundRect(
            if (i < cell.bars) lit else off,
            Offset((x + i * (BAR_W + BAR_GAP)) * u, (y + BARS_H - h) * u),
            Size(BAR_W * u, h * u),
            CornerRadius(2f * u),
            alpha = alpha,
        )
    }
}
