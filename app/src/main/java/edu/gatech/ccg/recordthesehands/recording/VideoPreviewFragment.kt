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

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.LayoutRes
import androidx.fragment.app.DialogFragment
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import edu.gatech.ccg.recordthesehands.R
import java.io.File

/**
 * Represents a video preview box. We use Android's built-in modals to allow us to easily
 * preview a video on top of the main activity.
 */
class VideoPreviewFragment(@LayoutRes layout: Int) : DialogFragment(layout) {

  companion object {
    private val TAG = VideoPreviewFragment::class.java.simpleName
  }

  /**
   * The video view in which to run the video.
   */
  private lateinit var videoView: PlayerView

  /**
   * The controller for playing back the video.
   */
  private var exoPlayer: ExoPlayer? = null

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

    startTimeMs = arguments?.getLong("startTimeMs") ?: 0
    endTimeMs = arguments?.getLong("endTimeMs") ?: 0
    landscape = arguments?.getBoolean("landscape") ?: false
    isTablet = arguments?.getBoolean("isTablet") ?: false

    Log.d(
      TAG,
      "startTimeMs $startTimeMs endTimeMs $endTimeMs landscape $landscape isTablet $isTablet"
    )
  }

  override fun onStop() {
    super.onStop()
    exoPlayer?.stop()
    exoPlayer?.release()
    exoPlayer = null
  }

  override fun onDestroyView() {
    exoPlayer?.release()
    super.onDestroyView()
  }

  /**
   * Initialize layout for the dialog box showing the video
   */
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    this.setStyle(STYLE_NO_FRAME, 0)

    Log.d(TAG, "onViewCreated")

    val relativePath = arguments?.getString("filepath")
    if (relativePath == null) {
      Log.e(TAG, "VideoPreviewFragment created without a filepath argument.")
      Toast.makeText(
        requireContext(),
        "Cannot play video: file path is missing.",
        Toast.LENGTH_LONG
      ).show()
      dismiss()
      return
    }

    val filepath = File(requireContext().filesDir, relativePath)
    if (!filepath.exists()) {
      Log.e(TAG, "Video file not found at path: $filepath")
      Toast.makeText(requireContext(), "Cannot play video: file not found.", Toast.LENGTH_LONG)
        .show()
      dismiss()
      return
    }

    Log.d(TAG, "Playing video from $filepath")

    // Set the video UI
    videoView = view.findViewById(R.id.videoPreview)

    exoPlayer = ExoPlayer.Builder(requireContext()).build().also { player ->
      videoView.player = player // videoView is now a PlayerView

      // Create a media source for the full video file
      val mediaItem = MediaItem.fromUri(Uri.fromFile(filepath))
      val mediaSource = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(requireContext()))
        .createMediaSource(mediaItem)

      // Create a clipping source to play only the desired segment
      val clippingMediaSource = ClippingMediaSource(
        mediaSource,
        startTimeMs * 1000, // Convert ms to microseconds
        endTimeMs * 1000    // Convert ms to microseconds
      )

      player.setMediaSource(clippingMediaSource)
      player.repeatMode = Player.REPEAT_MODE_ONE // Loop the clip
      player.prepare()
      player.play()
    }


    if (prompt != null) {
      // Sets the title text for the video
      val title = view.findViewById<TextView>(R.id.wordBeingSigned)
      title.text = prompt
    } else {
      val title = view.findViewById<TextView>(R.id.wordBeingSigned)
      title.visibility = View.GONE
    }
  }
}