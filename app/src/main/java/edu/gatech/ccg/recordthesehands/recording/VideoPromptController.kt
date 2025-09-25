/**
 * This file is part of Record These Hands, licensed under the MIT license.
 *
 * Copyright (c) 2021-2024
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
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import edu.gatech.ccg.recordthesehands.R
import java.io.File

/**
 * A simple class to manage a video player within a PlayerView element.
 */
class VideoPromptController(
  private val context: Context,
  private val activity: RecordingActivity?,
  private val videoView: PlayerView,
  private var videoPath: String,
  private val setConstraint: Boolean,
) {

  companion object {
    private val TAG = VideoPromptController::class.java.simpleName
  }

  /**
   * The video playback controller.
   */
  private var exoPlayer: ExoPlayer? = null

  /**
   * Initializes the controller.
   */
  init {
    Log.d(TAG, "Constructing VideoPromptController")

    exoPlayer = ExoPlayer.Builder(context).build().also {
      videoView.player = it
      it.repeatMode = Player.REPEAT_MODE_ONE
    }
    setPath(videoPath)

    if (activity != null) {
      // When we click on the video, it should expand to a larger
      // preview within the activity.
      videoView.setOnClickListener {
        val bundle = Bundle()
        bundle.putString("filepath", videoPath)
        bundle.putBoolean("landscape", true)

        val previewFragment = VideoPreviewFragment(R.layout.video_preview)
        previewFragment.arguments = bundle

        previewFragment.show(activity.supportFragmentManager, "videoPreview")
      }
    }
  }

  /**
   * Update the attached view's visibility.
   */
  fun setVisibility(visibility: Int) {
    videoView.visibility = visibility
  }

  /**
   * Sets the video player to play a video.
   */
  fun setPath(newPath: String) {
    videoPath = newPath
    Log.d(TAG, "Playing video from $videoPath")
    val videoFile = File(context.filesDir, videoPath)
    if (videoFile.exists()) {
      val mediaItem = MediaItem.fromUri(Uri.fromFile(videoFile))
      exoPlayer?.setMediaItem(mediaItem)
      exoPlayer?.prepare()
      exoPlayer?.play()
    } else {
      Log.w(TAG, "Video prompt file does not exist: $videoPath")
      videoView.visibility = View.GONE
    }
  }

  fun releasePlayer() {
    exoPlayer?.stop()
    exoPlayer?.release()
    exoPlayer = null
  }
}
