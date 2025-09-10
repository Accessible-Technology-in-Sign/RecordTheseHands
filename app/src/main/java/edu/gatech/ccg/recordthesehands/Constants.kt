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
  const val APP_VERSION = "2.1.0"

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
   * Result code for when the RecordingActivity finishes normally.
   */
  const val RESULT_OK = 0

  /**
   * Result code for when the RecordingActivity fails because we lost access to the camera.
   */
  const val RESULT_ACTIVITY_STOPPED = 1

  /**
   * Result code for when the RecordingActivity fails because we lost access to the camera.
   */
  const val RESULT_CAMERA_DIED = 2

  /**
   * Result code for when the RecordingActivity fails because we lost access to the camera.
   */
  const val RESULT_SURFACE_DESTROYED = 3

  /**
   * Result code for when the RecordingActivity fails because the recording file or buffer
   * failed for some reason.
   */
  const val RESULT_RECORDING_DIED = 4
}
