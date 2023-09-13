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
import java.util.*

/**
 * Represents a video preview box. We use Android's built-in modals to allow us to easily
 * preview a video on top of the main activity.
 */
class VideoPreviewFragment(@LayoutRes layout: Int): DialogFragment(layout),
     SurfaceHolder.Callback, MediaPlayer.OnPreparedListener {

    companion object {
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
     * If we are showing a tutorial to the user (when the press the "?" button in the prompt),
     * we will use this field instead of [recordingUri].
     */
    private var tutorialDesc: AssetFileDescriptor? = null

    /**
     * The word for this video.
     */
    lateinit var word: String

    /**
     * True if the video is in landscape. This is used for the tutorial recordings, as they are
     * all in landscape (relative to the locked portrait orientation for user recordings).
     */
    private var landscape = false

    /**
     * True if the playback is on a tablet. Note that this is used for the replays at the end
     * of the recording sessions, which are in either 3:4 (smartphone) or 4:3 (tablet), so
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
    var startTime: Long = 0

    /**
     * Time within the full recording that the clip ends.
     */
    private var endTime: Long = 0

    /**
     * Initialize instance variables for this fragment.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val word = arguments?.getString("word")!!
        this.word = word

        /**
         * If the "filename" argument was supplied, use the user's recording; otherwise,
         * use the "videos/`word`.mp4" internal asset as the file.
         */
        arguments?.getString("filename")?.let {
            this.recordingUri = Uri.fromFile(File(it))
        } ?: /* else */ run {
            context?.resources?.assets?.openFd("videos/$word.mp4")?.let {
                this.tutorialDesc = it
            }
        }

        // Set start, end, and landscape properties
        startTime = arguments?.getLong("startTime") ?: 0
        endTime = arguments?.getLong("endTime") ?: 0
        landscape = arguments?.getBoolean("landscape") ?: false
        isTablet = arguments?.getBoolean("isTablet") ?: false

        timer = Timer()
    }

    /**
     * Initialize layout for the dialog box showing the video
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        this.setStyle(STYLE_NO_FRAME, 0)

        // Set the video UI
        val videoView = view.findViewById<VideoView>(R.id.videoPreview)
        videoView.holder.addCallback(this)

        val layoutParams = videoView.layoutParams as ConstraintLayout.LayoutParams
        if (landscape) {
            layoutParams.dimensionRatio = "16:9"
        } else if (isTablet) {
            layoutParams.dimensionRatio = "4:3"
        }

        // Sets the title text for the video
        val title = view.findViewById<TextView>(R.id.wordBeingSigned)
        title.text = this.word
    }

    /**
     * Set up the video playback once a Surface (frame buffer) has been initialized
     * for that purpose.
     */
    override fun surfaceCreated(holder: SurfaceHolder) {
        // Set the data source
        this.mediaPlayer = MediaPlayer().apply {
            if (this@VideoPreviewFragment::recordingUri.isInitialized) {
                // Playing back the user's recording
                setDataSource(requireContext(), recordingUri)
            } else {
                // Playing back a tutorial video (if the user presses the help button)
                setDataSource(tutorialDesc!!)
            }

            setSurface(holder.surface)
            setOnPreparedListener(this@VideoPreviewFragment)
            prepareAsync()
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
        mp?.let {
            it.isLooping = true
            it.seekTo(startTime.toInt())
            it.start()
        }

        // If the startTime and endTime properties are set, set up a looper for the video.
        if (endTime > startTime) {
            val mTimerHandler = Handler()

            // Task to set playback to the beginning of the looped segment
            timerTask = object : TimerTask() {
                override fun run() {
                    mTimerHandler.post {
                        if (mediaPlayer.isPlaying) {
                            Log.i("VideoPreviewFragment", "Looping!")
                            mediaPlayer.seekTo(startTime.toInt())
                        }
                    }
                }
            }

            // Initializes the playback
            timer.schedule(timerTask,
                Calendar.getInstance().time,
                endTime - startTime + ENDING_BUFFER_TIME)
        }
    } // onPrepared()


}