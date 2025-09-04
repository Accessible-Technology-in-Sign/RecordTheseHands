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
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.widget.VideoView
import androidx.constraintlayout.widget.ConstraintLayout
import edu.gatech.ccg.recordthesehands.R
import java.io.File
import java.io.FileInputStream

/**
 * A simple class to manage a video player within a VideoView element.
 */
class VideoPromptController(
  private val context: Context,
  private val activity: RecordingActivity?,
  private val videoView: VideoView,
  private var videoPath: String,
  private val setConstraint: Boolean,
) : SurfaceHolder.Callback, MediaPlayer.OnPreparedListener {

  companion object {
    private val TAG = VideoPromptController::class.java.simpleName
  }

  /**
   * The video playback controller.
   */
  private var mediaPlayer: MediaPlayer? = null

  /**
   * A fileInputStream for the file to be used.
   */
  private var fileInputStream: FileInputStream? = null

  /**
   * Initializes the controller.
   */
  init {
    Log.d(TAG, "Constructing VideoPromptController")

    setPath(videoPath)

    if (activity != null) {
      // When we click on the video, it should expand to a larger
      // preview within the activity.
      videoView.setOnClickListener {
        val bundle = Bundle()
        bundle.putString("filepath", videoPath)
        bundle.putBoolean("landscape", true)

        val previewFragment = VideoPreviewFragment(R.layout.recording_preview)
        previewFragment.arguments = bundle

        previewFragment.show(activity!!.supportFragmentManager, "videoPreview")
      }
    }
  }

  /**
   * Update the attached view's visibility.
   */
  fun setVisibility(visibility: Int) {
    videoView.visibility = visibility
  }

  fun destroyFileInputStream() {
    if (fileInputStream != null) {
      fileInputStream!!.close()
      fileInputStream = null
    }
  }

  fun createFileInputStream() {
    destroyFileInputStream()
    val filepath = File(context.filesDir, videoPath)
    if (filepath.exists()) {
      fileInputStream = FileInputStream(filepath)
    }
  }

  /**
   * Sets the video player to play a video.
   */
  fun setPath(newPath: String) {
    videoPath = newPath

    Log.d(TAG, "Playing video from $videoPath")

    // If the media player already exists, just change its data source and start playback again.
    mediaPlayer?.let {
      it.stop()
      it.reset()
      destroyFileInputStream()
      createFileInputStream()
      if (fileInputStream != null) {
        it.setDataSource(fileInputStream!!.fd)
        it.setOnPreparedListener(this)
        it.prepareAsync()
      }
    }

    // If the media player has not been initialized, set up the video player.
    // We will set up the data source from the surfaceCreated() function
      ?: run {
        videoView.holder.addCallback(this)
      }
  }

  fun releasePlayer() {
    this.mediaPlayer?.let {
      it.stop()
      it.release()
    }
    destroyFileInputStream()
  }

  /**
   * When the videoView's pixel buffer is set up, this function is called. The MediaPlayer object
   * will then target the videoView's canvas.
   */
  override fun surfaceCreated(holder: SurfaceHolder) {
    createFileInputStream()
    mediaPlayer = MediaPlayer().apply {
      fileInputStream?.let {
        setDataSource(it.fd)
        setSurface(holder.surface)
        setOnPreparedListener(this@VideoPromptController)
        prepareAsync()
      }
    }
  }

  /**
   * We don't use surfaceChanged right now since the same surface is used for the entire
   * duration of the activity.
   */
  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    // TODO("Not yet implemented")
  }

  /**
   * When the recording activity is finished, we can free the memory used by the media player.
   */
  override fun surfaceDestroyed(holder: SurfaceHolder) {
    releasePlayer()
  }

  /**
   * This function is called when the video file is ready, this function makes it so that
   * the video loops and then starts playing back.
   */
  override fun onPrepared(mp: MediaPlayer?) {
    mp?.let {
      it.isLooping = true
      if (setConstraint) {
        val layoutParams = videoView.layoutParams as ConstraintLayout.LayoutParams
        val constraint = "${layoutParams.dimensionRatio[0]},${it.videoWidth}:${it.videoHeight}"
        Log.d(TAG, "Setting video constraint to ${constraint}")
        layoutParams.dimensionRatio = constraint
        videoView.requestLayout()
      }
      it.start()
    }
  }

}
