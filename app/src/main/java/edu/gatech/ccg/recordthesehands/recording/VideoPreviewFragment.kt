/**
 * VideoPreviewFragment.kt
 * This file is part of Record These Hands, licensed under the MIT license.
 *
 * Copyright (c) 2021-23
 *   Georgia Institute of Technology
 *   Authors:
 *     Sahir Shahryar <contact@sahirshahryar.com>
 *     Matthew So <matthew.so@gatech.edu>
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

import android.content.res.AssetFileDescriptor
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.widget.TextView
import android.widget.VideoView
import androidx.annotation.LayoutRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.DialogFragment
import edu.gatech.ccg.recordthesehands.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*

/**
 * Represents a video preview box. We use Android's built-in modals to allow us to easily
 * preview a video on top of the main activity.
 */
class VideoPreviewFragment(@LayoutRes layout: Int) : DialogFragment(layout),
  SurfaceHolder.Callback, MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener {

  companion object {
    private val TAG = VideoPreviewFragment::class.java.simpleName

    /**
     * Since playback cuts out early from testing, adding an extra half second
     * to the end of a sign's playback could be beneficial to users.
     */
    const val ENDING_BUFFER_TIME: Long = 500
  }

  /**
   * The controller for playing back the video.
   */
  lateinit var mediaPlayer: MediaPlayer

  /**
   * If we are showing a replay of a user's performance, this field will be used.
   */
  private lateinit var recordingUri: Uri

  /**
   * A fileInputStream for the file to be used.
   */
  private lateinit var fileInputStream: FileInputStream

  /**
   * If we are showing a tutorial to the user (when the press the "?" button in the prompt),
   * we will use this field instead of [recordingUri].
   */
  private var tutorialDesc: AssetFileDescriptor? = null

  /**
   * The word for this video.
   */
  lateinit var prompt: String

  /**
   * True if the video is in landscape. This is used for the tutorial recordings, as they are
   * all in landscape (relative to the locked portrait orientation for user recordings).
   */
  private var landscape = false

  /**
   * True if the playback is on a tablet. Note that this is used for the replays at the end
   * of the recording sessions, which are in either 3:4 (smartphone) or 4:3 (tablet),
   * so this setting and `landscape` (above) should be mutually exclusive.
   */
  private var isTablet = false

  /**
   * A timer and associated task to loop the video.
   */
  private lateinit var timer: Timer
  private lateinit var timerTask: TimerTask

  /**
   * Time within the full recording that the clip starts.
   */
  var startTimeMs: Long = 0

  /**
   * Time within the full recording that the clip ends.
   */
  private var endTimeMs: Long = 0

  /**
   * Initialize instance variables for this fragment.
   */
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    prompt = arguments?.getString("prompt")!!

    // TODO handle a missing filepath.
    val filepath = File(requireContext().filesDir, arguments?.getString("filepath")!!)
    fileInputStream = FileInputStream(filepath)
    Log.d(TAG, "Playing video from $filepath")
    Log.d(TAG, "file exists: " + filepath.exists().toString())
    Log.d(TAG, "file readable: " + filepath.canRead().toString())
    Log.d(TAG, "file length: " + filepath.length().toString())

    startTimeMs = arguments?.getLong("startTimeMs") ?: 0
    endTimeMs = arguments?.getLong("endTimeMs") ?: 0
    landscape = arguments?.getBoolean("landscape") ?: false
    isTablet = arguments?.getBoolean("isTablet") ?: false

    Log.d(TAG, "startTimeMs $startTimeMs endTimeMs $endTimeMs landscape $landscape isTablet $isTablet")

    timer = Timer()
  }

  override fun onDestroy() {
    fileInputStream.close()
    super.onDestroy()
  }

  /**
   * Initialize layout for the dialog box showing the video
   */
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    this.setStyle(STYLE_NO_FRAME, 0)

    Log.d(TAG, "onViewCreated")

    // Set the video UI
    val videoView = view.findViewById<VideoView>(R.id.videoPreview)
    videoView.holder.addCallback(this)
    videoView.visibility = View.VISIBLE

    val layoutParams = videoView.layoutParams as ConstraintLayout.LayoutParams
    if (landscape) {
      layoutParams.dimensionRatio = "H,16:9"
    } else if (isTablet) {
      layoutParams.dimensionRatio = "H,4:3"
    } else {
      layoutParams.dimensionRatio = "H,3:4"
    }

    // Sets the title text for the video
    val title = view.findViewById<TextView>(R.id.wordBeingSigned)
    title.text = prompt
  }

  /**
   * Set up the video playback once a Surface (frame buffer) has been initialized
   * for that purpose.
   */
  override fun surfaceCreated(holder: SurfaceHolder) {
    // Set the data source
    Log.d(TAG, "surfaceCreated")
    mediaPlayer = MediaPlayer()

    // Passing a filepath or Uri to the file in the app directory gives a permission
    // denied error (actually a generic error).  Instead, we need to produce a file descriptor
    // directly and pass it as the source.  We maintain the FileInputStream for the entire
    // duration that the file descriptor is in use.
    mediaPlayer.setDataSource(fileInputStream.fd)

    mediaPlayer.setSurface(holder.surface)
    mediaPlayer.setOnPreparedListener(this)
    mediaPlayer.setOnErrorListener(this)
    mediaPlayer.prepareAsync()
  }

  /**
   * Used if the Surface is changed. Not currently used.
   */
  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    // TODO("Not currently used")
  }

  /**
   * Stop video playback if the Surface is destroyed.
   */
  override fun surfaceDestroyed(holder: SurfaceHolder) {
    Log.d(TAG, "surfaceDestroyed")
    view?.findViewById<VideoView>(R.id.videoPreview)?.holder?.removeCallback(this)
    timer.cancel()
    this.mediaPlayer.let {
      it.stop()
      it.reset()
      it.release()
    }
    tutorialDesc?.close()
  }

  /**
   * When the MediaPlayer is prepared, start playback.
   */
  override fun onPrepared(mp: MediaPlayer?) {
    // Loop and start the video
    Log.d(TAG, "onPrepared")
    mp?.let {
      it.isLooping = true
      it.seekTo(startTimeMs.toInt())
      it.start()
    }

    // If the startTimeMs and endTimeMs properties are set, set up a looper for the video.
    if (endTimeMs > startTimeMs) {
      val mTimerHandler = Handler()

      // Task to set playback to the beginning of the looped segment
      timerTask = object : TimerTask() {
        override fun run() {
          mTimerHandler.post {
            if (mediaPlayer.isPlaying) {
              Log.i("VideoPreviewFragment", "Looping!")
              mediaPlayer.seekTo(startTimeMs.toInt())
            }
          }
        }
      }

      // Initializes the playback
      timer.schedule(
        timerTask,
        Calendar.getInstance().time,
        endTimeMs - startTimeMs + ENDING_BUFFER_TIME
      )
    }
  } // onPrepared()

  override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
    Log.e(TAG, "MediaPlayer error occurred. what = $what extra = $extra")
    return false
  }
}
