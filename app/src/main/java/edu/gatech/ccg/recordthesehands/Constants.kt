/**
 * This file is part of Record These Hands, licensed under the MIT license.
 *
 * Copyright (c) 2023-2024
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
package edu.gatech.ccg.recordthesehands

object Constants {
  /**
   * The app version (used in server communications).
   */
  const val APP_VERSION = "2.3.5"

  /**
   * The size, in inches, at which we should consider the user's device a tablet.
   */
  const val TABLET_SIZE_THRESHOLD_INCHES = 7.0f

  /**
   * The maximum number of recording sessions the user can
   * do within one launch of the app. Once this limit is reached, the user
   * needs to close the app via the multitasking menu, then relaunch to keep
   * recording. This was primarily introduced to resolve a memory leak that
   * would cause crashes when the app was left running for too long, but it
   * also encourages the user to record in a diverse range of backgrounds
   * and lighting conditions.
   */
  const val MAX_RECORDINGS_IN_SITTING = 20

  /**
   * Result code for when the RecordingActivity fails because we lost access to the camera.
   */
  const val RESULT_ACTIVITY_FAILED = 1

  /**
   * Result code for when the RecordingActivity fails because we lost access to the camera.
   */
  const val RESULT_ACTIVITY_STOPPED = 2

  /**
   * Result code for when the RecordingActivity fails because we lost access to the camera.
   */
  const val RESULT_ACTIVITY_UNREACHABLE = 3

  /**
   * Record video at 15 Mbps. At 1944x2592 @ 30 fps, this level of detail should be more
   * than high enough.
   */
  const val RECORDER_VIDEO_BITRATE: Int = 30_000_000
  const val MAXIMUM_RESOLUTION = 9_000_000

  /**
   * Camera framerate.
   */
  const val RECORDING_FRAMERATE = 30

  /**
   * The number of prompts to use in each recording session.
   */
  const val DEFAULT_SESSION_LENGTH = 30
  const val DEFAULT_TUTORIAL_SESSION_LENGTH = 5

  /**
   * Since playback cuts out early from testing, adding an extra half second
   * to the end of a sign's playback could be beneficial to users.
   */
  const val VIDEO_PREVIEW_ENDING_BUFFER_TIME: Long = 500

  const val UPLOAD_NOTIFICATION_ID = 1
  const val UPLOAD_NOTIFICATION_CHANNEL_ID = "upload_service"

  /**
   * The filename for the prompts file.
   */
  const val PROMPTS_FILENAME = "prompts.json"

  private val COUNTDOWN_DURATION_BASE = 15 * 60 * 1000L
  private val RECORDING_HARD_STOP_DURATION_BASE = COUNTDOWN_DURATION_BASE + 3 * 60 * 1000L

  private val UPLOAD_RESUME_ON_STARTUP_TIMEOUT_BASE = 30L * 1000L
  private val UPLOAD_PERIODIC_TIMEOUT_BASE = 10L * 60L * 1000L
  private val UPLOAD_RESUME_ON_IDLE_TIMEOUT_BASE = 10L * 60L * 1000L
  private val UPLOAD_RESUME_ON_ACTIVITY_FINISHED_BASE = 5L * 1000L
  private val UPLOAD_RESUME_ON_STOP_RECORDING_TIMEOUT_BASE = 10L * 60L * 1000L

  private val TIMEOUT_FRACTION: Float by lazy {
    val context = RecordTheseHands.applicationContext()
    val resourceId =
      context.resources.getIdentifier("timeout_fraction", "fraction", context.packageName)
    if (resourceId != 0) {
      context.resources.getFraction(resourceId, 1, 1)
    } else {
      1f
    }
  }

  val COUNTDOWN_DURATION = if (TIMEOUT_FRACTION < .99) {
    (TIMEOUT_FRACTION * COUNTDOWN_DURATION_BASE).toLong()
  } else {
    COUNTDOWN_DURATION_BASE
  }
  val RECORDING_HARD_STOP_DURATION = if (TIMEOUT_FRACTION < .99) {
    (TIMEOUT_FRACTION * RECORDING_HARD_STOP_DURATION_BASE).toLong()
  } else {
    RECORDING_HARD_STOP_DURATION_BASE
  }

  val UPLOAD_RESUME_ON_STARTUP_TIMEOUT = if (TIMEOUT_FRACTION < .99) {
    (TIMEOUT_FRACTION * UPLOAD_RESUME_ON_STARTUP_TIMEOUT_BASE).toLong()
  } else {
    UPLOAD_RESUME_ON_STARTUP_TIMEOUT_BASE
  }
  val UPLOAD_PERIODIC_TIMEOUT = if (TIMEOUT_FRACTION < .99) {
    (TIMEOUT_FRACTION * UPLOAD_PERIODIC_TIMEOUT_BASE).toLong()
  } else {
    UPLOAD_PERIODIC_TIMEOUT_BASE
  }
  val UPLOAD_RESUME_ON_IDLE_TIMEOUT = if (TIMEOUT_FRACTION < .99) {
    (TIMEOUT_FRACTION * UPLOAD_RESUME_ON_IDLE_TIMEOUT_BASE).toLong()
  } else {
    UPLOAD_RESUME_ON_IDLE_TIMEOUT_BASE
  }
  val UPLOAD_RESUME_ON_ACTIVITY_FINISHED = if (TIMEOUT_FRACTION < .99) {
    (TIMEOUT_FRACTION * UPLOAD_RESUME_ON_ACTIVITY_FINISHED_BASE).toLong()
  } else {
    UPLOAD_RESUME_ON_ACTIVITY_FINISHED_BASE
  }
  val UPLOAD_RESUME_ON_STOP_RECORDING_TIMEOUT = if (TIMEOUT_FRACTION < .99) {
    (TIMEOUT_FRACTION * UPLOAD_RESUME_ON_STOP_RECORDING_TIMEOUT_BASE).toLong()
  } else {
    UPLOAD_RESUME_ON_STOP_RECORDING_TIMEOUT_BASE
  }

}
