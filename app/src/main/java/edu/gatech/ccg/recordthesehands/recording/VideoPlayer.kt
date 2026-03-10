/**
 * This file is part of Record These Hands, licensed under the MIT license.
 *
 * Copyright (c) 2021-2025
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package edu.gatech.ccg.recordthesehands.recording

import android.content.Context
import android.graphics.Outline
import android.net.Uri
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.VideoView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/**
 * A composable that plays a video from the app's internal storage, using Android's
 * [VideoView] wrapped in [AndroidView]. The video auto-plays on composition and stops
 * when disposed.
 *
 * The aspect ratio is automatically determined from the video's dimensions once it is
 * prepared. A [fallbackAspectRatio] is used until the video is loaded.
 *
 * @param resourcePath Path relative to [Context.getFilesDir] where the video is stored.
 * @param modifier Modifier for the video player container.
 * @param cornerRadius Corner radius for rounded corners on the video.
 * @param loop Whether the video should loop continuously.
 * @param fallbackAspectRatio Aspect ratio to use before the video dimensions are known.
 */
@Composable
fun VideoPlayer(
    resourcePath: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    loop: Boolean = true,
    fallbackAspectRatio: Float = 16f / 9f
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }

    var aspectRatio by remember(resourcePath) { mutableFloatStateOf(fallbackAspectRatio) }

    val videoView = remember(resourcePath) {
        VideoView(context).apply {
            val file = File(context.filesDir, resourcePath)
            setVideoURI(Uri.fromFile(file))
            setOnPreparedListener { mp ->
                val w = mp.videoWidth
                val h = mp.videoHeight
                if (w > 0 && h > 0) {
                    aspectRatio = w.toFloat() / h.toFloat()
                }
                mp.isLooping = loop
                start()
            }
        }
    }

    DisposableEffect(resourcePath) {
        onDispose {
            videoView.stopPlayback()
        }
    }

    AndroidView(
        factory = { _ ->
            videoView.apply {
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                    }
                }
                clipToOutline = true
            }
        },
        modifier = modifier.aspectRatio(aspectRatio)
    )
}
