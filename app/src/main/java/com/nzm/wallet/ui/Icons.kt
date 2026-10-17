package com.nzm.wallet.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block
    ).build()

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx + r, cy)
    arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
    arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
    close()
}

object Ic {
    val Lock = icon("lock") {
        moveTo(5f, 11f); horizontalLineTo(19f); verticalLineTo(20f); horizontalLineTo(5f); close()
        moveTo(8f, 11f); verticalLineTo(8f); arcTo(4f, 4f, 0f,
        isMoreThanHalf = false,
        isPositiveArc = true,
        x1 = 16f,
        y1 = 8f
    ); verticalLineTo(11f)
    }
    val Copy = icon("copy") {
        moveTo(9f, 9f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(9f); close()
        moveTo(5f, 15f); verticalLineTo(4f); horizontalLineTo(16f)
    }
    val ChevronDown = icon("chevronDown") {
        moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
    }
    val ChevronRight = icon("chevronRight") {
        moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f)
    }
    val ChevronLeft = icon("chevronLeft") {
        moveTo(15f, 6f); lineTo(9f, 12f); lineTo(15f, 18f)
    }
    val Send = icon("send") {
        moveTo(7f, 17f); lineTo(17f, 7f)
        moveTo(9f, 7f); lineTo(17f, 7f); lineTo(17f, 15f)
    }
    val Plus = icon("plus") {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    }
    val Check = icon("check") {
        moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7.5f)
    }
    val Code = icon("code") {
        moveTo(9f, 8f); lineTo(5f, 12f); lineTo(9f, 16f)
        moveTo(15f, 8f); lineTo(19f, 12f); lineTo(15f, 16f)
    }
    val Rows = icon("rows") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 17f); lineTo(14f, 17f)
    }
    val Clock = icon("clock") {
        circle(12f, 12f, 8f)
        moveTo(12f, 8f); lineTo(12f, 12f); lineTo(15f, 14f)
    }
    val Sliders = icon("sliders") {
        moveTo(5f, 8f); horizontalLineTo(14f)
        moveTo(18f, 8f); horizontalLineTo(19f)
        moveTo(5f, 16f); horizontalLineTo(6f)
        moveTo(10f, 16f); horizontalLineTo(19f)
        circle(16f, 8f, 2f)
        circle(8f, 16f, 2f)
    }
    val Trash = icon("trash") {
        moveTo(5f, 7f); lineTo(19f, 7f)
        moveTo(10f, 7f); lineTo(10f, 4f); lineTo(14f, 4f); lineTo(14f, 7f)
        moveTo(7f, 7f); lineTo(8f, 20f); lineTo(16f, 20f); lineTo(17f, 7f)
    }
    val Refresh = icon("refresh") {
        moveTo(21f, 12f)
        arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 3f)
        curveToRelative(2.52f, 0f, 4.93f, 1f, 6.74f, 2.74f)
        lineTo(21f, 8f)
        moveTo(21f, 3f); verticalLineToRelative(5f); horizontalLineToRelative(-5f)
    }
}