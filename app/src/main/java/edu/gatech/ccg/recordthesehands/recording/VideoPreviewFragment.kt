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

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.annotation.LayoutRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.DialogFragment
import edu.gatech.ccg.recordthesehands.R
import java.io.File
import java.io.FileInputStream
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
   * The video view in which to run the video.
   */
  private lateinit var videoView: VideoView

  /**
   * The controller for playing back the video.
   */
  private var mediaPlayer: MediaPlayer? = null

  /**
   * A fileInputStream for the file to be used.
   */
  private var fileInputStream: FileInputStream? = null

  /**
   * The text prompt for this video.
   */
  private var prompt: String? = null

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
    prompt = arguments?.getString("prompt")

    val filepath = File(requireContext().filesDir, arguments?.getString("filepath")!!)
    if (filepath.exists()) {
      fileInputStream = FileInputStream(filepath)
    }
    Log.d(TAG, "Playing video from $filepath")

    startTimeMs = arguments?.getLong("startTimeMs") ?: 0
    endTimeMs = arguments?.getLong("endTimeMs") ?: 0
    landscape = arguments?.getBoolean("landscape") ?: false
    isTablet = arguments?.getBoolean("isTablet") ?: false

    Log.d(TAG, "startTimeMs $startTimeMs endTimeMs $endTimeMs landscape $landscape isTablet $isTablet")

    timer = Timer()
  }

  override fun onDestroy() {
    if (fileInputStream != null) {
      fileInputStream!!.close()
      fileInputStream = null
    }
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
    videoView = view.findViewById<VideoView>(R.id.videoPreview)
    videoView.holder.addCallback(this)
    videoView.visibility = View.VISIBLE

    if (prompt != null) {
      // Sets the title text for the video
      val title = view.findViewById<TextView>(R.id.wordBeingSigned)
      title.text = prompt
    } else {
      val title = view.findViewById<TextView>(R.id.wordBeingSigned)
      title.visibility = View.GONE
    }
  }

  /**
   * Set up the video playback once a Surface (frame buffer) has been initialized
   * for that purpose.
   */
  override fun surfaceCreated(holder: SurfaceHolder) {
    // Set the data source
    Log.d(TAG, "surfaceCreated")

    // TODO create a message if the file does not exist.
    if (fileInputStream != null) {
      mediaPlayer = MediaPlayer().also {
        // Passing a filepath or Uri to the file in the app directory gives a permission
        // denied error (actually a generic error).  Instead, we need to produce a file descriptor
        // directly and pass it as the source.  We maintain the FileInputStream for the entire
        // duration that the file descriptor is in use.
        it.setDataSource(fileInputStream!!.fd)

        it.setSurface(holder.surface)
        it.setOnPreparedListener(this)
        it.setOnErrorListener(this)
        it.prepareAsync()
      }
    } else {
      val text = "Video file not found"
      val toast = Toast.makeText(activity, text, Toast.LENGTH_SHORT)
      toast.show()
    }
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
    mediaPlayer?.let {
      it.stop()
      it.reset()
      it.release()
    }
  }

  /**
   * When the MediaPlayer is prepared, start playback.
   */
  override fun onPrepared(mp: MediaPlayer?) {
    // Loop and start the video
    Log.d(TAG, "onPrepared")
    mp?.let {
      it.isLooping = true

      val layoutParams = videoView.layoutParams as ConstraintLayout.LayoutParams
      val constraint = "${layoutParams.dimensionRatio[0]},${it.videoWidth}:${it.videoHeight}"
      Log.d(TAG, "Setting video constraint to ${constraint}")
      layoutParams.dimensionRatio = constraint
      videoView.requestLayout()

      it.seekTo(startTimeMs.toInt())
      it.start()
    }

    // If the startTimeMs and endTimeMs properties are set, set up a looper for the video.
    if (endTimeMs > startTimeMs) {
      // Task to set playback to the beginning of the looped segment
      timerTask = object : TimerTask() {
        override fun run() {
          if (mediaPlayer?.isPlaying == true) {
            Log.i("VideoPreviewFragment", "Looping!")
            mediaPlayer!!.seekTo(startTimeMs.toInt())
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
