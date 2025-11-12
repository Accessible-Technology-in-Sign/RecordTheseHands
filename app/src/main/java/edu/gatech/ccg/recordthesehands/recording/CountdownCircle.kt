package edu.gatech.ccg.recordthesehands.recording

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import edu.gatech.ccg.recordthesehands.upload.DataManager
import kotlin.math.ceil

@Composable
fun CountdownCircle(
  modifier: Modifier = Modifier,
  dataManager: DataManager,
  durationMs: Int = 20000,
  key: Any? = Unit,
  componentSize: Dp = 100.dp,
  strokeWidthProportion: Float = 0.2f,
  showText: Boolean = true,
  onFinished: () -> Unit,
) {
  val animatable = remember { Animatable(0f) }
  var clickCount by remember { mutableStateOf(0) }
  val appSettings by dataManager.appSettings.observeAsState()

  LaunchedEffect(key) {
    animatable.snapTo(0f)
    animatable.animateTo(
      targetValue = 1f,
      animationSpec = tween(durationMillis = durationMs, easing = LinearEasing)
    )
    onFinished()
  }

  val animatedProgress = animatable.value

  Canvas(
    modifier = modifier
      .size(componentSize)
      .then(
        if (appSettings?.enableDismissCountdownCircle == true) {
          Modifier.pointerInput(Unit) {
            detectTapGestures(
              onTap = {
                clickCount++
                if (clickCount >= 1) {
                  onFinished()
                }
              }
            )
          }
        } else {
          Modifier
        }
      )
  ) {
    val strokeWidth = size.minDimension * strokeWidthProportion
    val diameter = size.minDimension - strokeWidth
    val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
    val arcSize = Size(diameter, diameter)

    // Draw the red background circle
    drawArc(
      color = Color.Red,
      startAngle = 0f,
      sweepAngle = 360f,
      useCenter = false,
      topLeft = topLeft,
      size = arcSize,
      style = Stroke(width = strokeWidth)
    )

    // Draw the green sweep arc
    drawArc(
      color = Color.Green,
      startAngle = -90f,
      sweepAngle = 360 * animatedProgress,
      useCenter = false,
      topLeft = topLeft,
      size = arcSize,
      style = Stroke(width = strokeWidth)
    )

    val remainingSeconds = ceil((durationMs * (1 - animatedProgress)) / 1000f).toInt()
    val text = remainingSeconds.toString()
    val paint = Paint().asFrameworkPaint().apply {
      isAntiAlias = true
      textSize = 40f
      color = android.graphics.Color.WHITE
      textAlign = android.graphics.Paint.Align.CENTER
    }

    if (showText) {
      drawIntoCanvas {
        it.nativeCanvas.drawText(
          text,
          center.x,
          center.y - (paint.descent() + paint.ascent()) / 2,
          paint
        )
      }
    }
  }
}
