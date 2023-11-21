/**
 * RecordingActivity.kt
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

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.animation.AnimationSet
import android.view.animation.CycleInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import androidx.viewpager2.widget.ViewPager2
import edu.gatech.ccg.recordthesehands.Constants.RESULT_ACTIVITY_STOPPED
import edu.gatech.ccg.recordthesehands.Constants.RESULT_CAMERA_DIED
import edu.gatech.ccg.recordthesehands.Constants.TABLET_SIZE_THRESHOLD_INCHES
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.databinding.ActivityRecordTabletBinding
import edu.gatech.ccg.recordthesehands.padZeroes
import edu.gatech.ccg.recordthesehands.sendEmail
import edu.gatech.ccg.recordthesehands.upload.DataManager
import edu.gatech.ccg.recordthesehands.upload.Prompt
import edu.gatech.ccg.recordthesehands.upload.Prompts
import edu.gatech.ccg.recordthesehands.upload.UploadService
import edu.gatech.ccg.recordthesehands.toHex
import kotlinx.coroutines.CoroutineScope
import java.io.File
import kotlin.collections.ArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.lang.Integer.min
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Contains the data for a clip within the greater recording.
 *
 * @param file       (String) The filename for the (overall) video recording.
 * @param videoStart (Instant) The timestamp that the overall video recording started at.
 * @param signStart  (Instant) The timestamp that the clip within the video started at.
 * @param signEnd    (Instant) The timestamp that the clip within the video ended at.
 * @param attempt    (Int) An attempt number for this phrase key in this session.
 */
class ClipDetails(
  val clipId: String, val sessionId: String, val filename: String, val prompt: Prompt, val videoStart: Instant,
) {

  companion object {
    private val TAG = ClipDetails::class.java.simpleName
  }

  var startButtonDownTimestamp: Instant? = null
  var startButtonUpTimestamp: Instant? = null
  var restartButtonDownTimestamp: Instant? = null
  var swipeBackTimestamp: Instant? = null
  var swipeForwardTimestamp: Instant? = null

  var lastModifiedTimestamp: Instant? = null
  var valid = true

  fun toJson(): JSONObject {
    val json = JSONObject()
    json.put("clipId", clipId)
    json.put("sessionId", sessionId)
    json.put("filename", filename)
    json.put("promptData", prompt.toJson())
    json.put("videoStart", DateTimeFormatter.ISO_INSTANT.format(videoStart))
    if (startButtonDownTimestamp != null) {
      json.put(
        "startButtonDownTimestamp",
        DateTimeFormatter.ISO_INSTANT.format(startButtonDownTimestamp)
      )
    }
    if (startButtonUpTimestamp != null) {
      json.put(
        "startButtonUpTimestamp",
        DateTimeFormatter.ISO_INSTANT.format(startButtonUpTimestamp)
      )
    }
    if (restartButtonDownTimestamp != null) {
      json.put(
        "restartButtonDownTimestamp",
        DateTimeFormatter.ISO_INSTANT.format(restartButtonDownTimestamp)
      )
    }
    if (swipeBackTimestamp != null) {
      json.put("swipeBackTimestamp", DateTimeFormatter.ISO_INSTANT.format(swipeBackTimestamp))
    }
    if (swipeForwardTimestamp != null) {
      json.put("swipeForwardTimestamp", DateTimeFormatter.ISO_INSTANT.format(swipeForwardTimestamp))
    }
    if (lastModifiedTimestamp != null) {
      json.put("lastModifiedTimestamp", lastModifiedTimestamp)
    }
    json.put("valid", valid)
    return json
  }

  fun signStart(): Instant? {
    return startButtonDownTimestamp ?: startButtonUpTimestamp
  }

  fun signEnd(): Instant? {
    return restartButtonDownTimestamp ?: swipeForwardTimestamp ?: swipeBackTimestamp
  }

  /**
   * Creates a string representation for this recording.
   */
  override fun toString(): String {
    val json = toJson()
    return json.toString(2)
  }
}

suspend fun DataManager.saveClipData(clipDetails: ClipDetails) {
  val json = clipDetails.toJson()
  // Use a consistent key based on the clipId so that any changes to the clip
  // will be updated on the server.
  addKeyValue("clipData-${clipDetails.clipId}", json)
}

fun Random.Default.nextHexId(numBytes: Int): String {
  val bytes = ByteArray(numBytes)
  nextBytes(bytes)
  return toHex(bytes)
}

fun Context.filenameToFilepath(filename: String): File {
  return File(
    filesDir,
    File.separator + "upload" + File.separator + filename
  )
}

/**
 * Class to handle all the information about the recording session which should be saved
 * to the server.
 */
class RecordingSessionInfo(
  val sessionId: String, val filename: String, val deviceId: String, val username: String,
  val sessionType: String, val initialPromptIndex: Int, val limitPromptIndex: Int) {
  companion object {
    private val TAG = RecordingSessionInfo::class.java.simpleName
  }

  var result = "ONGOING"
  var startTimestamp: Instant? = null
  var endTimestamp: Instant? = null
  var finalPromptIndex = initialPromptIndex

  fun toJson(): JSONObject {
    val json = JSONObject()
    json.put("sessionId", sessionId)
    json.put("result", result)
    json.put("filename", filename)
    json.put("deviceId", deviceId)
    json.put("username", username)
    json.put("sessionType", sessionType)
    json.put("initialPromptIndex", initialPromptIndex)
    json.put("limitPromptIndex", limitPromptIndex)
    json.put("finalPromptIndex", finalPromptIndex)
    if (startTimestamp != null) {
      json.put(
        "startTimestamp",
        DateTimeFormatter.ISO_INSTANT.format(startTimestamp)
      )
    }
    if (endTimestamp != null) {
      json.put(
        "endTimestamp",
        DateTimeFormatter.ISO_INSTANT.format(endTimestamp)
      )
    }
    return json
  }

  override fun toString(): String {
    val json = toJson()
    return json.toString(2)
  }
}

suspend fun DataManager.saveSessionInfo(sessionInfo: RecordingSessionInfo) {
  val json = sessionInfo.toJson()
  // Use a consistent key so that any changes will be updated on the server.
  addKeyValue("sessionData-${sessionInfo.sessionId}", json)
}

/**
 * This class handles the recording of ASL into videos.
 *
 * @author  Matthew So <matthew.so@gatech.edu>, Sahir Shahryar <contact@sahirshahryar.com>
 * @since   October 4, 2021
 * @version 1.1.0
 */
class RecordingActivity : AppCompatActivity() {
  companion object {
    private val TAG = RecordingActivity::class.java.simpleName

    /**
     * Record video at 15 Mbps. At 1944x2592 @ 30 fps, this level of detail should be more
     * than high enough.
     */
    private const val RECORDER_VIDEO_BITRATE: Int = 15_000_000

    /**
     * Height, width, and frame rate of the video recording. Using a 4:3 aspect ratio allows us
     * to get the widest possible field of view on a Pixel 4a camera, which has a 4:3 sensor.
     * Any other aspect ratio would result in some degree of cropping.
     */
    private const val RECORDING_HEIGHT = 2592
    private const val RECORDING_WIDTH = 1944
    private const val RECORDING_FRAMERATE = 30

    private const val MAXIMUM_RESOLUTION = 6_000_000

    /**
     * Whether or not an instructional video should be shown to the user.
     */
    private const val SHOW_INSTRUCTION_VIDEO = false

    /**
     * The length of the countdown (in milliseconds), after which the recording will end
     * automatically. Currently configured to be 15 minutes.
     */
    private const val COUNTDOWN_DURATION = 15 * 60 * 1000L

    /**
     * The number of prompts to use in each recording session.
     */
    private const val DEFAULT_SESSION_LENGTH = 30
    private const val DEFAULT_TUTORIAL_SESSION_LENGTH = 5
  }


  // UI elements
  /**
   * Big red button used to start/stop a clip. (Note that we are continuously recording;
   * the button only marks when the user started or stopped signing to the camera.)
   *
   * Note that this button can be either a FloatingActionButton or a Button, depending on
   * whether we are on a smartphone or a tablet, respectively.
   */
  lateinit var recordButton: View

  /**
   * The big button to finish the session.
   */
  lateinit var finishedButton: View

  /**
   * The button to restart a recording.
   */
  lateinit var restartButton: View

  /**
   * The UI that allows a user to swipe back and forth and make recordings.
   * The end screens are also included in this ViewPager.
   */
  lateinit var sessionPager: ViewPager2

  /**
   * The UI that shows how much time is left on the recording before it auto-concludes.
   */
  lateinit var countdownText: TextView

  /**
   * The recording light and text.
   */
  private lateinit var recordingLightView: View

  /**
   * The recording preview.
   */
  lateinit var cameraView: SurfaceView

  /**
   * The instructional video, if [SHOW_INSTRUCTION_VIDEO] is true. If false, this value will
   * be null.
   */
  private var tutorialView: VideoTutorialController? = null

  // UI state variables
  /**
   * Marks whether the user is using a tablet (diagonal screen size > 7.0 inches (~17.78 cm)).
   */
  private var isTablet = false


  /**
   * Marks whether or not the recording button is enabled. If not, then the button should be
   * invisible, and it should be neither clickable (tappable) nor focusable.
   */
  private var recordButtonEnabled = false

  /**
   * Marks whether or not the camera has been successfully initialized. This is used to prevent
   * parts of the code related to camera initialization from running multiple times.
   */
  private var cameraInitialized = false

  /**
   * Marks whether or not the user is currently signing a word. This is essentially only true
   * for the duration that the user holds down the Record button.
   */
  private var isSigning = false

  /**
   * Marks whether or not the camera is currently recording or not. We record continuously as soon
   * as the activity launches, so this value will be true in some instances that `isSigning` may
   * be false.
   */
  private var isRecording = false

  /**
   * In the event that the user is holding down the Record button when the timer runs out, this
   * value will be set to true. Once the user releases the button, the session will end
   * immediately.
   */
  private var endSessionOnClipEnd = false

  /**
   * The page of the ViewPager UI that the user is currently on. If there are K words that have
   * been selected for the user to record, indices 0 to K - 1 (inclusive) are the indices
   * corresponding to those words, index K is the index for the "Swipe right to end recording"
   * page, and index K + 1 is the recording summary page.
   */
  private var currentPage: Int = 0

  /**
   * A timer for the recording, which starts with a time limit of `COUNTDOWN_DURATION` and
   * shows its current value in `countdownText`. When the timer expires, the recording
   * automatically stops and the user is taken to the summary screen.
   */
  private lateinit var countdownTimer: CountDownTimer

  // Prompt data
  /**
   * The prompts data.
   */
  lateinit var prompts: Prompts

  // Recording and session data
  /**
   * The filename for the current video recording.
   */
  private lateinit var filename: String

  /**
   * The file handle for the current video recording.
   */
  private lateinit var outputFile: File

  /**
   * Information on all the clips collected in this session.
   */
  val clipData = ArrayList<ClipDetails>()

  /**
   * Information for the current clip (should be the last item in clipData).
   */
  private var currentClipDetails: ClipDetails? = null

  /**
   * An index used to create unique clipIds.
   */
  private var clipIdIndex = 0

  /**
   * The dataManager object for communicating with the server.
   */
  lateinit var dataManager: DataManager

  /**
   * The username.
   */
  private lateinit var username: String

  /**
   * The start index of the session.
   */
  var sessionStartIndex = -1

  /**
   * The limit index for this session.  Meaning, one past the last prompt index.
   */
  var sessionLimit = -1

  /**
   * General information about the recording session.
   */
  private lateinit var sessionInfo: RecordingSessionInfo

  /**
   * If the app is in tutorial mode.
   */
  private var tutorialMode = false

  /**
   * The time at which the recording session started.
   */
  private lateinit var sessionStartTime: Instant

  /**
   * Because the email and password have been put in a .gitignored file for security
   * reasons, the app is designed to not completely fail to compile if those constants are
   * missing. The constants' existence is checked in SplashScreenActivity and if any of them
   * don't exist, sending email confirmations is disabled. See the README for information on
   * how to set these constants, if that functionality is desired.
   */
  private var emailConfirmationEnabled: Boolean = false


  // Camera API variables
  /**
   * The thread for handling camera-related actions.
   */
  private lateinit var cameraThread: HandlerThread

  /**
   * The Handler object for accessing the camera. We primarily use this when initializing or
   * shutting down the camera.
   */
  private lateinit var cameraHandler: Handler

  /**
   * The buffer to which camera frames are projected. This is used by the MediaRecorder to
   * record the video
   */
  private lateinit var recordingSurface: Surface

  /**
   * The camera recorder instance, used to set up and control the recording settings.
   */
  private lateinit var recorder: MediaRecorder


  /**
   * A CameraCaptureSession object, which functions as a wrapper for handling / stopping the
   * recording.
   */
  private lateinit var session: CameraCaptureSession

  /**
   * Details of the camera being used to record the video.
   */
  private lateinit var camera: CameraDevice

  /**
   * Window insets controller for hiding and showing the toolbars.
   */
  var windowInsetsController: WindowInsetsControllerCompat? = null

  /**
   * The buffer to which the camera sends frames for the purposes of displaying a live preview
   * (rendered in the `cameraView`).
   */
  private var previewSurface: Surface? = null

  /**
   * The operating system's camera service. We can get camera information from this service.
   */
  private val cameraManager: CameraManager by lazy {
    val context = this.applicationContext
    context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
  }


  // Permissions
  /**
   * Marks whether the user has enabled the necessary permissions to record successfully. If
   * we don't check this, the app will crash instead of presenting an error.
   */
  private var permissions: Boolean = true

  /**
   * When the activity starts, this routine checks the CAMERA and WRITE_EXTERNAL_STORAGE
   * permissions. (We do not need the MICROPHONE permission as we are just recording silent
   * videos.)
   */
  val permission =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
      map.entries.forEach { entry ->
        when (entry.key) {
          Manifest.permission.CAMERA ->
            permissions = permissions && entry.value
        }
      }
    }

  /**
   * A function to initialize a new thread for camera-related code to run on.
   */
  private fun generateCameraThread() = HandlerThread("CameraThread").apply { start() }

  /**
   * Generates a new [Surface] for storing recording data, which will promptly be assigned to
   * the [recordingSurface] field above.
   */
  private fun createRecordingSurface(recordingSize: Size): Surface {
    val surface = MediaCodec.createPersistentInputSurface()
    recorder = MediaRecorder(this)

    outputFile = applicationContext.filenameToFilepath(filename)
    if (outputFile.parentFile?.let { !it.exists() } ?: false) {
      Log.i(TAG, "creating directory ${outputFile.parentFile}.")
      outputFile.parentFile?.mkdirs()
    }

    setRecordingParameters(recorder, surface, recordingSize).prepare()

    return surface
  }

  /**
   * Prepares a [MediaRecorder] using the given surface.
   */
  private fun setRecordingParameters(rec: MediaRecorder, surface: Surface, recordingSize: Size) =
    rec.apply {
      // Set the video settings from our predefined constants.
      setVideoSource(MediaRecorder.VideoSource.SURFACE)
      setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
      setOutputFile(outputFile.absolutePath)
      setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)

      setVideoFrameRate(RECORDING_FRAMERATE)
      setVideoSize(recordingSize.width, recordingSize.height)

      setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
      setInputSurface(surface)

      /**
       * The orientation of 270 degrees (-90 degrees) was determined through
       * experimentation. For now, we do not need to support other
       * orientations than the default portrait orientation.
       *
       * The tablet orientation of 0 degrees is designed primarily to support the use of
       * a Pixel Tablet (2023) with its included stand (although any tablet with a stand
       * may suffice).
       */
      setOrientationHint(if (isTablet) 0 else 270)
    }

  private fun checkCameraPermission(): Boolean {
    /**
     * First, check camera permissions. If the user has not granted permission to use the
     * camera, give a prompt asking them to grant that permission in the Settings app, then
     * relaunch the app.
     */
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {

      val errorRoot = findViewById<ConstraintLayout>(R.id.main_root)
      val errorMessage = layoutInflater.inflate(
        R.layout.permission_error, errorRoot,
        false
      )
      errorRoot.addView(errorMessage)

      // Since the user hasn't granted camera permissions, we need to stop here.
      return false
    }

    return true
  }

  private fun getFrontCamera(): String {
    for (id in cameraManager.cameraIdList) {
      val face = cameraManager.getCameraCharacteristics(id)
        .get(CameraCharacteristics.LENS_FACING)
      if (face == CameraSelector.LENS_FACING_FRONT) {
        return id
      }
    }

    throw IllegalStateException("No front camera available")
  }

  /**
   * This code initializes the camera-related portion of the code, adding listeners to enable
   * video recording as long as we hold down the Record button.
   */
  @SuppressLint("ClickableViewAccessibility")
  private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
    if (!checkCameraPermission()) {
      return@launch
    }

    /**
     * User has given permission to use the camera. First, find the front camera. If no
     * front-facing camera is available, crash. (This shouldn't fail on any modern
     * smartphone.)
     */
    val cameraId = getFrontCamera()

    /**
     * Open the front-facing camera.
     */
    camera = openCamera(cameraManager, cameraId, cameraHandler)

    /**
     * Send video feed to both [previewSurface] and [recordingSurface], then start the
     * recording.
     */
    val targets = listOf(previewSurface!!, recordingSurface)
    session = createCaptureSession(camera, targets, cameraHandler)

    startRecording()

    recordButton.setOnTouchListener { view, event ->
      return@setOnTouchListener recordButtonOnTouchListener(view, event)
    }

    restartButton.setOnTouchListener { view, event ->
      return@setOnTouchListener restartButtonOnTouchListener(view, event)
    }

    finishedButton.setOnTouchListener { view, event ->
      return@setOnTouchListener finishedButtonOnTouchListener(view, event)
    }

  }

  private fun newClipId(): String {
    val output = "${sessionInfo.sessionId}-${padZeroes(clipIdIndex, 3)}"
    clipIdIndex += 1
    return output
  }

  private fun recordButtonOnTouchListener(view: View, event: MotionEvent): Boolean {
    /**
     * Do nothing if the record button is disabled.
     */
    if (!recordButtonEnabled) {
      return false
    }

    when (event.action) {
      MotionEvent.ACTION_DOWN -> lifecycleScope.launch(Dispatchers.IO) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val now = Instant.now()
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
        dataManager.logToServerAtTimestamp(timestamp, "recordButton down")
        currentClipDetails =
          ClipDetails(newClipId(), sessionInfo.sessionId, filename,
          prompts.array[sessionStartIndex + currentPage], sessionStartTime)
        currentClipDetails!!.startButtonDownTimestamp = now
        currentClipDetails!!.lastModifiedTimestamp = now
        clipData.add(currentClipDetails!!)
        dataManager.saveClipData(currentClipDetails!!)

        isSigning = true
        runOnUiThread {
          animateGoText()
        }
      }

      MotionEvent.ACTION_UP -> lifecycleScope.launch(Dispatchers.IO) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
        val now = Instant.now()
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
        dataManager.logToServerAtTimestamp(timestamp, "recordButton up")
        if (currentClipDetails != null) {
          currentClipDetails!!.startButtonUpTimestamp = now
          currentClipDetails!!.lastModifiedTimestamp = now
          dataManager.saveClipData(currentClipDetails!!)
        }
        runOnUiThread {
          setButtonState(recordButton, false)
          setButtonState(restartButton, true)
          setButtonState(finishedButton, false)
        }
      }
    }
    return true
  }

  private fun finishedButtonOnTouchListener(view: View, event: MotionEvent): Boolean {

    Log.d(TAG, "finishedButtonOnTouchListener ${event}")
    when (event.action) {
      MotionEvent.ACTION_DOWN -> lifecycleScope.launch(Dispatchers.IO) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        dataManager.logToServer("finishedButton down")
        goToSummaryPage()
      }

      MotionEvent.ACTION_UP -> lifecycleScope.launch(Dispatchers.IO) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
        Log.e(TAG, "Finished button should already be gone.")
        dataManager.logToServer("finishedButton up")
      }
    }
    return true
  }
  private fun restartButtonOnTouchListener(view: View, event: MotionEvent): Boolean {

    when (event.action) {
      MotionEvent.ACTION_DOWN -> lifecycleScope.launch(Dispatchers.IO) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val now = Instant.now()
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
        dataManager.logToServerAtTimestamp(timestamp, "restartButton down")
        val lastClipDetails = currentClipDetails!!
        lastClipDetails.restartButtonDownTimestamp = now
        lastClipDetails.lastModifiedTimestamp = now
        lastClipDetails.valid = false
        dataManager.saveClipData(lastClipDetails)

        currentClipDetails =
          ClipDetails(newClipId(), sessionInfo.sessionId,
            filename, prompts.array[sessionStartIndex + currentPage], sessionStartTime)
        currentClipDetails!!.startButtonDownTimestamp = now
        currentClipDetails!!.lastModifiedTimestamp = now
        clipData.add(currentClipDetails!!)
        dataManager.saveClipData(currentClipDetails!!)

        isSigning = true
        runOnUiThread {
          setButtonState(recordButton, false)
          setButtonState(restartButton, true)
          setButtonState(finishedButton, false)
          animateGoText()
        }
      }

      MotionEvent.ACTION_UP -> lifecycleScope.launch(Dispatchers.IO) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
        val now = Instant.now()
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
        dataManager.logToServerAtTimestamp(timestamp, "restartButton up")
        if (currentClipDetails != null) {
          currentClipDetails!!.startButtonUpTimestamp = now
          currentClipDetails!!.lastModifiedTimestamp = now
          dataManager.saveClipData(currentClipDetails!!)
        }
      }
    }
    return true
  }

  fun goToSummaryPage() {
    runOnUiThread {
      // Move to the next prompt and allow the user to swipe back and forth.
      sessionPager.setCurrentItem(sessionLimit - sessionStartIndex + 1, false)
      sessionPager.isUserInputEnabled = false
    }

    isSigning = false
  }

  /**
   * Adds a callback to the camera view, which is used primarily to assign a value to
   * [previewSurface] once Android has finished creating the Surface for us. We need this
   * because we cannot initialize a Surface object directly but we still need to be able to
   * pass a Surface object around to the UI, which uses the contents of the Surface (buffer) to
   * render the camera preview.
   */
  private fun setupCameraCallback() {
    cameraView.holder.addCallback(object : SurfaceHolder.Callback {
      /**
       * Called when the OS has finished creating a surface for us.
       */
      override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Initializing surface!")
        previewSurface = holder.surface
        initializeCamera()
      }

      /**
       * Called if the surface had to be reassigned. In practical usage thus far, we have
       * not run into any issues here by not reassigning [previewSurface] when this callback
       * is triggered.
       */
      override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        Log.d(TAG, "New format, width, height: $format, $w, $h")
        Log.d(TAG, "Camera preview surface changed!")
      }

      /**
       * Called when the surface is destroyed. Typically this will occur when the activity
       * closes.
       */
      override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Camera preview surface destroyed!")
        previewSurface = null
      }
    })
  }


  /**
   * Opens up the requested Camera for a recording session.
   *
   * @suppress linter for "MissingPermission": acceptable here because this function is only
   * ever called from [initializeCamera], which exits before calling this function if camera
   * permission has been denied.
   */
  @SuppressLint("MissingPermission")
  private suspend fun openCamera(
    manager: CameraManager,
    cameraId: String,
    handler: Handler? = null
  ): CameraDevice = suspendCancellableCoroutine { caller ->
    manager.openCamera(
      cameraId, object : CameraDevice.StateCallback() {
        /**
         * Once the camera has been successfully opened, resume execution in the calling
         * function.
         */
        override fun onOpened(device: CameraDevice) {
          Log.d(TAG, "openCamera: New camera created with ID $cameraId")
          caller.resume(device)
        }

        /**
         * If the camera is disconnected, end the activity and return to the splash screen.
         */
        override fun onDisconnected(device: CameraDevice) {
          Log.e(TAG, "openCamera: Camera $cameraId has been disconnected")
          this@RecordingActivity.apply {
            sessionInfo.result = "RESULT_CAMERA_DIED"
            stopRecorder()
            setResult(RESULT_CAMERA_DIED)
            finish()
          }
        }

        /**
         * If there's an error while opening the camera, pass that exception to the
         * calling function.
         */
        override fun onError(device: CameraDevice, error: Int) {
          val msg = when (error) {
            ERROR_CAMERA_DEVICE -> "Fatal (device)"
            ERROR_CAMERA_DISABLED -> "Device policy"
            ERROR_CAMERA_IN_USE -> "Camera in use"
            ERROR_CAMERA_SERVICE -> "Fatal (service)"
            ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
            else -> "Unknown"
          }
          val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
          Log.e(TAG, exc.message, exc)
          if (caller.isActive) {
            caller.resumeWithException(exc)
          }
        }
      },

      // Pass this code onto the camera handler thread
      handler
    )
  }

  /**
   * Create a CameraCaptureSession. This is required by the camera API
   */
  private suspend fun createCaptureSession(
    device: CameraDevice,
    targets: List<Surface>,
    handler: Handler? = null
  ): CameraCaptureSession = suspendCoroutine { cont ->
    /**
     * Set up the camera capture session with the success / failure handlers defined below
     */
    device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
      // Capture session successfully configured - resume execution
      override fun onConfigured(session: CameraCaptureSession) {
        cont.resume(session)
      }

      // Capture session config failed - throw exception
      override fun onConfigureFailed(session: CameraCaptureSession) {
        val exc = RuntimeException("Camera ${device.id} session configuration failed")
        Log.e(TAG, exc.message, exc)
        cont.resumeWithException(exc)
      }
    }, handler)
  }


  /**
   * Starts the camera recording once we have device and capture session information within
   * [initializeCamera].
   */
  private fun startRecording() {
    // Lock screen orientation
    this@RecordingActivity.requestedOrientation =
      ActivityInfo.SCREEN_ORIENTATION_LOCKED

    /**
     * Create a request to record at 30fps.
     */
    val cameraRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
      // Add the previewSurface buffer as a destination for the camera feed if it exists
      previewSurface?.let {
        addTarget(it)
      }

      // Add the recording buffer as a destination for the camera feed
      addTarget(recordingSurface)

      // Lock FPS at 30
      set(
        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
        Range(RECORDING_FRAMERATE, RECORDING_FRAMERATE)
      )

      // Disable video stabilization. This ensures that we don't get a cropped frame
      // due to software stabilization.
      set(
        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
      )
      set(
        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
      )
    }.build()

    session.setRepeatingRequest(cameraRequest, null, cameraHandler)

    UploadService.pauseUploadTimeout(COUNTDOWN_DURATION + UploadService.UPLOAD_RESUME_ON_IDLE_TIMEOUT)
    isRecording = true

    recorder.start()
    sessionStartTime = Instant.now()

    CoroutineScope(Dispatchers.IO).launch {
      sessionInfo.startTimestamp = sessionStartTime
      dataManager.saveSessionInfo(sessionInfo)

      val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
      val json = JSONObject()
      json.put("filename", filename)
      json.put("startTimestamp", sessionStartTime)
      dataManager.addKeyValue("recording_started-${timestamp}", json)
    }

    setButtonState(recordButton, true)
    recordButtonEnabled = true

    // Set up the countdown timer.
    countdownText = findViewById(R.id.timerLabel)
    countdownTimer = object : CountDownTimer(COUNTDOWN_DURATION, 1000) {
      // Update the timer text every second.
      override fun onTick(p0: Long) {
        val rawSeconds = (p0 / 1000).toInt() + 1
        val minutes = padZeroes(rawSeconds / 60, 2)
        val seconds = padZeroes(rawSeconds % 60, 2)
        countdownText.text = "$minutes:$seconds"
      }

      // When the timer expires, move to the summary page (or have the app move there as soon
      // as the user finishes the recording they're currently working on).
      override fun onFinish() {
        if (isSigning) {
          // TODO test this.
          endSessionOnClipEnd = true
        } else {
          // TODO test this.
          sessionPager.currentItem = prompts.array.size + 1
        }
      }
    } // CountDownTimer

    countdownTimer.start()

    recordingLightView.visibility = View.VISIBLE
  }

  fun setButtonState(button: View, visible: Boolean) {
    if (visible) {
      button.visibility = View.VISIBLE
      // button.isClickable = true
      // button.isFocusable = true
    } else {
      button.visibility = View.GONE
      // button.isClickable = false
      // button.isFocusable = false
    }
  }


  /**
   * Handler code for when the activity restarts. Right now, we return to the splash screen if the
   * user exits mid-session, as the app is continuously recording throughout this activity's
   * lifespan.
   */
  override fun onRestart() {
    super.onRestart()
    Log.e(TAG, "RecordingActivity.onRestart() called which should be impossible.")
    if (isRecording) {
      sessionInfo.result = "RESULT_ACTIVITY_STOPPED"
      stopRecorder()
      setResult(RESULT_ACTIVITY_STOPPED)
    }
    dataManager.logToServer("onRestart called.")
    finish()
  }

  /**
   * Handles stopping the recording session.
   */
  override fun onStop() {
    windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
    Log.d(TAG, "Recording Activity: onStop")
    try {
      dataManager.logToServer("onStop called.")
      if (isRecording) {
        sessionInfo.result = "RESULT_ACTIVITY_STOPPED"
        stopRecorder()
        setResult(RESULT_ACTIVITY_STOPPED)
      }
      /**
       * This is remnant code from when we were attempting to find and fix a memory leak
       * that occurred if the user did too many recording sessions in one sitting. It is
       * unsure whether this helped; however, we will leave it as-is for now.
       */
      sessionPager.adapter = null
      super.onStop()
    } catch (exc: Throwable) {
      Log.e(TAG, "Error in RecordingActivity.onStop()", exc)
    }
    UploadService.pauseUploadTimeout(UploadService.UPLOAD_RESUME_ON_STOP_RECORDING_TIMEOUT)
    CoroutineScope(Dispatchers.IO).launch {
      // It's important that UploadService has a pause signal at this point, so that in the
      // unlikely event that we have been idle for the full amount of time and the video is
      // uploading, it will abort and we can acquire the lock in a reasonable amount of time.
      dataManager.persistData()
    }
    finish()
  }

  /**
   * Handle the activity being destroyed.
   */
  override fun onDestroy() {
    try {
      super.onDestroy()
    } catch (exc: Throwable) {
      Log.e(TAG, "Error in RecordingActivity.onDestroy()", exc)
    }
  }

  private fun stopRecorder() {
    if (isRecording) {
      isRecording = false
      Log.i(TAG, "stopRecorder: stopping recording.")

      recorder.stop()
      session.stopRepeating()
      session.close()
      recorder.release()
      camera.close()
      cameraThread.quitSafely()
      recordingSurface.release()
      countdownTimer.cancel()
      cameraHandler.removeCallbacksAndMessages(null)

      runOnUiThread {
        recordingLightView.visibility = View.GONE
      }

      CoroutineScope(Dispatchers.IO).launch {
        val now = Instant.now()
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
        val json = JSONObject()
        json.put("filename", filename)
        json.put("endTimestamp", timestamp)
        dataManager.addKeyValue("recording_stopped-${timestamp}", json)
        dataManager.registerFile(outputFile.relativeTo(applicationContext.filesDir).path)
        prompts.savePromptIndex()
        sessionInfo.endTimestamp = now
        sessionInfo.finalPromptIndex = prompts.promptIndex
        dataManager.saveSessionInfo(sessionInfo)
        dataManager.updateLifetimeStatistics(
          Duration.between(sessionInfo.startTimestamp, sessionInfo.endTimestamp)
        )
        // Persist the data.  This will lock the dataManager for a few seconds, which is
        // only acceptable because we are not recording.
        dataManager.persistData()
      }
      Log.d(TAG, "Email confirmations enabled? = $emailConfirmationEnabled")
      if (emailConfirmationEnabled) {
        sendConfirmationEmail()
      }
      Log.i(TAG, "stopRecorder: finished")
    } else {
      Log.i(TAG, "stopRecorder: called with isRecording == false")
    }
  }

  /**
   * Entry point for the RecordingActivity.
   */
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    windowInsetsController =
      WindowCompat.getInsetsController(window, window.decorView)?.also {
        it.systemBarsBehavior =
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }

    dataManager = DataManager(applicationContext)

    // Calculate the display size to determine whether to use mobile or tablet layout.
    val displayMetrics = resources.displayMetrics
    val heightInches = displayMetrics.heightPixels / displayMetrics.ydpi
    val widthInches = displayMetrics.widthPixels / displayMetrics.xdpi
    val diagonal = sqrt((heightInches * heightInches) + (widthInches * widthInches))
    Log.i(TAG, "Computed screen size: $diagonal inches")

    val binding: ViewBinding
    if (diagonal > TABLET_SIZE_THRESHOLD_INCHES) {
      isTablet = true
      binding = ActivityRecordTabletBinding.inflate(this.layoutInflater)
    } else {
      // TODO Remove the phone layout and rename tablet layout.
      binding = ActivityRecordTabletBinding.inflate(this.layoutInflater)
    }

    val view = binding.root
    setContentView(view)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    // Set up view pager
    this.sessionPager = findViewById(R.id.sessionPager)

    // Fetch word data, user id, etc. from the splash screen activity which
    // initiated this activity
    val bundle = this.intent.extras ?: Bundle()

    emailConfirmationEnabled = bundle.getBoolean("SEND_CONFIRMATION_EMAIL")

    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

    runBlocking {
      prompts = dataManager.getPrompts() ?: throw IllegalStateException("prompts not available.")
      username = dataManager.getUsername() ?: throw IllegalStateException("username not available.")
      tutorialMode = dataManager.getTutorialMode()
      val sessionType = if (tutorialMode) "tutorial" else "normal"
      val sessionId = dataManager.newSessionId()
      if (tutorialMode) {
        filename = "tutorial-${username}-${sessionId}-${timestamp}.mp4"
      } else {
        filename = "${username}-${sessionId}-${timestamp}.mp4"
      }
      sessionStartIndex = prompts.promptIndex
      val sessionLength =
          if (tutorialMode) DEFAULT_TUTORIAL_SESSION_LENGTH else DEFAULT_SESSION_LENGTH
      sessionLimit = min(prompts.array.size, prompts.promptIndex + sessionLength)
      sessionInfo = RecordingSessionInfo(
        sessionId, filename, dataManager.getDeviceId(), username, sessionType,
        sessionStartIndex, sessionLimit
      )
      dataManager.saveSessionInfo(sessionInfo)
    }

    dataManager.logToServer(
        "Setting up recording with filename ${filename} for prompts " +
            "[${prompts.promptIndex}, ${sessionLimit})")

    // Set title bar text
    title = "${prompts.promptIndex + 1} of ${prompts.array.size}"

    // Enable record button
    recordButton = findViewById(R.id.recordButton)
    recordButton.isHapticFeedbackEnabled = true
    setButtonState(recordButton, true)

    restartButton = findViewById(R.id.restartButton)
    restartButton.isHapticFeedbackEnabled = true
    setButtonState(restartButton, false)

    finishedButton = findViewById(R.id.finishedButton)
    finishedButton.isHapticFeedbackEnabled = true
    setButtonState(finishedButton, false)

    sessionPager.adapter = WordPagerAdapter(this)

    if (SHOW_INSTRUCTION_VIDEO) {
      // TODO This is broken.  Add in ability to download videos and show them.
      val videoView: VideoView = findViewById(R.id.demoVideo)
      this.tutorialView = VideoTutorialController(this, videoView, "none")
    } else {
      val videoView: VideoView = findViewById(R.id.demoVideo)
      videoView.visibility = View.GONE
    }

    // Set up swipe handler for the word selector UI
    sessionPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
      /**
       * Page changed
       */
      override fun onPageSelected(position: Int) {
        Log.d(
          TAG,
          "onPageSelected(${position}) sessionPager.currentItem ${sessionPager.currentItem} currentPage (before updating) ${currentPage}"
        )
        if (currentClipDetails != null) {
          val now = Instant.now()
          val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
          if (currentPage < sessionPager.currentItem) {
            // Swiped forward (currentPage is still the old value)
            currentClipDetails!!.swipeForwardTimestamp = now
          } else {
            // Swiped backwards (currentPage is still the old value)
            currentClipDetails!!.swipeBackTimestamp = now
          }
          currentClipDetails!!.lastModifiedTimestamp = now
          val saveClipDetails = currentClipDetails!!
          currentClipDetails = null
          CoroutineScope(Dispatchers.IO).launch {
            dataManager.saveClipData(saveClipDetails)
          }
        }
        currentPage = sessionPager.currentItem
        super.onPageSelected(currentPage)
        if (endSessionOnClipEnd) {
          prompts.promptIndex += 1
          goToSummaryPage()
          return
        }
        val promptIndex = sessionStartIndex + currentPage

        if (promptIndex < sessionLimit) {
          dataManager.logToServer("selected page for promptIndex ${promptIndex}")
          prompts.promptIndex = promptIndex
          runOnUiThread {
            title = "${prompts.promptIndex + 1} of ${prompts.array.size}"

            setButtonState(recordButton, true)
            setButtonState(restartButton, false)
            setButtonState(finishedButton, false)
          }
        } else if (promptIndex == sessionLimit) {
          dataManager.logToServer("selected last chance page (promptIndex ${promptIndex})")
          prompts.promptIndex = sessionLimit
          /**
           * Page to give the user a chance to swipe back and record more before
           * finishing.
           */
          title = ""

          setButtonState(recordButton, false)
          setButtonState(restartButton, false)
          setButtonState(finishedButton, true)
        } else {
          dataManager.logToServer("selected corrections page (promptIndex ${promptIndex})")
          title = ""

          setButtonState(recordButton, false)
          setButtonState(restartButton, false)
          setButtonState(finishedButton, false)
          sessionPager.isUserInputEnabled = false

          UploadService.pauseUploadTimeout(UploadService.UPLOAD_RESUME_ON_IDLE_TIMEOUT)
          sessionInfo.result = "ON_CORRECTIONS_PAGE"
          stopRecorder()
        }
      }
    })

    // Set up the camera preview's size
    cameraView = findViewById(R.id.cameraPreview)
    cameraView.holder.setSizeFromLayout()

    val aspectRatioConstraint = findViewById<ConstraintLayout>(R.id.aspectRatioConstraint)
    val layoutParams = aspectRatioConstraint.layoutParams
    layoutParams.height = layoutParams.width * 4 / 3
    aspectRatioConstraint.layoutParams = layoutParams

    recordingLightView = findViewById(R.id.recordingLight)

    recordingLightView.visibility = View.GONE
  }

  private fun animateGoText() {
    val goText = findViewById<TextView>(R.id.goText)
    goText.visibility = View.VISIBLE

    // Set the pivot point for SCALE_X and SCALE_Y transformations to the
    // top-left corner of the zoomed-in view. The default is the center of
    // the view.
    //binding.expandedImage.pivotX = 0f
    //binding.expandedImage.pivotY = 0f

    // Construct and run the parallel animation of the four translation and
    // scale properties: X, Y, SCALE_X, and SCALE_Y.
    var expandAnimator = AnimatorSet().apply {
      play(
        ObjectAnimator.ofFloat(
          goText,
          View.SCALE_X,
          .5f,
          2f
        )
      ).apply {
        with(
          ObjectAnimator.ofFloat(
            goText,
            View.SCALE_Y,
            .5f,
            2f
          )
        )
      }
      duration = 500
      interpolator = CycleInterpolator(0.5f)
      addListener(object : AnimatorListenerAdapter() {

        override fun onAnimationEnd(animation: Animator) {
          // currentAnimator = null
          goText.visibility = View.GONE
        }

        override fun onAnimationCancel(animation: Animator) {
          // currentAnimator = null
          goText.visibility = View.GONE
        }
      })
      start()
    }
    /*
    var contractAnimator = AnimatorSet().apply {
      play(
        ObjectAnimator.ofFloat(
          goText,
          View.SCALE_X,
          2f,
          0f
        )
      ).apply {
        with(
          ObjectAnimator.ofFloat(
            goText,
            View.SCALE_Y,
            2f,
            0f
          )
        )
      }
      duration = 10000
      interpolator = DecelerateInterpolator()
      addListener(object : AnimatorListenerAdapter() {

        override fun onAnimationEnd(animation: Animator) {
          // currentAnimator = null
          goText.visibility = View.GONE
        }

        override fun onAnimationCancel(animation: Animator) {
          // currentAnimator = null
          goText.visibility = View.GONE
        }
      })
      start()
    }
    val animations = AnimationSet(false)
    animations.addAnimation(expandAnimator)
    animations.addAnimation(contractAnimator)
    */
  }

  /**
   * Handle activity resumption (typically from multitasking)
   * TODO there is a mismatch between when things are deallocated in onStop and where they
   * TODO are initialized in onResume.
   */
  override fun onResume() {
    super.onResume()

    windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
    // Create camera thread
    cameraThread = generateCameraThread()
    cameraHandler = Handler(cameraThread.looper)

    val cameraId = getFrontCamera()
    val props = cameraManager.getCameraCharacteristics(cameraId)

    val sizes = props.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    Log.i(TAG, sizes.toString())

    fun hasAspectRatio(heightRatio: Int, widthRatio: Int, dim: Size): Boolean {
      val target = heightRatio.toFloat() / widthRatio.toFloat()
      return ((dim.width.toFloat() / dim.height.toFloat()) - target < 0.01)
    }

    val heightRatio = if (isTablet) 4 else 3
    val widthRatio = if (isTablet) 3 else 4
    val mainAspectRatio = findViewById<ConstraintLayout>(R.id.aspectRatioConstraint)
    (mainAspectRatio.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio =
        "H,${heightRatio}:${widthRatio}"

    val largestAvailableSize = sizes?.getOutputSizes(ImageFormat.JPEG)?.filter {
      // Find a resolution smaller than the maximum pixel count (6 MP)
      // with an aspect ratio of either 4:3 or 3:4, depending on whether we are using
      // a tablet or not.
      it.width * it.height < MAXIMUM_RESOLUTION
          && (hasAspectRatio(heightRatio, widthRatio, it))
    }?.maxByOrNull { it.width * it.height }

    val chosenSize = largestAvailableSize ?: Size(RECORDING_HEIGHT, RECORDING_WIDTH)


    Log.i(TAG, "Selected video resolution: ${chosenSize.width} x ${chosenSize.height}")
    recordingSurface = createRecordingSurface(chosenSize)

    /**
     * If we already finished the recording activity, no need to restart the camera thread
     */
    if (sessionPager.currentItem >= prompts.array.size) {
      return
    } else if (!cameraInitialized) {
      setupCameraCallback()
      cameraInitialized = true
    }
  }

  /**
   * Finish the recording session and close the activity.
   */
  fun concludeRecordingSession() {
    sessionInfo.result = "RESULT_OK"
    stopRecorder()
    runBlocking {
      dataManager.saveSessionInfo(sessionInfo)
    }
    setResult(RESULT_OK)

    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notification = dataManager.createNotification(
      "Recording Session Completed", "still need to upload")
    notificationManager.notify(UploadService.NOTIFICATION_ID, notification)

    finish()
  }

  /**
   * Returns whether the current activity is running in tablet mode. Used by the video previews
   * on the summary screen to determine whether the video preview needs to be swapped to 4:3
   * (instead of 3:4).
   */
  fun isTablet(): Boolean {
    return isTablet
  }


  /**
   * Handles the creation and sending of a confirmation email, allowing us to track
   * the user's progress.
   */
  private fun sendConfirmationEmail() {
    // TODO test this code.
    val output = JSONObject()
    val clips = JSONArray()
    for (i in 0..clipData.size-1) {
      val clipDetails = clipData[i]
      clips.put(i, clipDetails.toJson())
    }
    output.put("sessionInfo", sessionInfo.toJson())
    output.put("clips", clips)

    val subject = "Recording confirmation for $username"

    val body = "The user '$username' recorded ${clipData.size} clips for prompts index range " +
        "${sessionStartIndex} to ${sessionLimit} into " +
        "file $filename\n\n" +
        output.toString(2) + "\n\n"

    thread {
      Log.d(TAG, "Running thread to send email...")

      /**
       * Send the email from `sender` (authorized by `password`) to the emails in
       * `recipients`. The reason we don't use `R.string.confirmation_email_sender` is
       * that the file containing these credentials is not published to the Internet,
       * so people downloading this repository would face compilation errors unless
       * they create this file for themselves (which is detailed in the README).
       *
       * To let people get up and running quickly, we just check for the existence of
       * these string resources manually instead of making people create an app password
       * in Gmail for what is really just an optional component of the app.
       */
      val senderStringId = resources.getIdentifier(
        "confirmation_email_sender",
        "string", packageName
      )
      val passwordStringId = resources.getIdentifier(
        "confirmation_email_password",
        "string", packageName
      )
      val recipientArrayId = resources.getIdentifier(
        "confirmation_email_recipients",
        "array", packageName
      )

      val sender = resources.getString(senderStringId)
      val password = resources.getString(passwordStringId)
      val recipients = ArrayList(listOf(*resources.getStringArray(recipientArrayId)))

      sendEmail(sender, recipients, subject, body, password)
    }
  }

} // RecordingActivity

/**
 * A simple class to manage the video player at the top of the screen. See
 * TODO update this to to download videos from the server (pushed as directives)
 * TODO and access them with a key here.
 * [RecordingActivity.tutorialView].
 */
class VideoTutorialController(
  private val activity: RecordingActivity,
  private val videoView: VideoView,
  initialWord: String
) : SurfaceHolder.Callback, MediaPlayer.OnPreparedListener {

  companion object {
    private val TAG = VideoTutorialController::class.java.simpleName
  }

  /**
   * The currently selected word.
   */
  private var word: String = initialWord

  /**
   * The video file being played back.
   */
  private var dataSource: AssetFileDescriptor? = null

  /**
   * The video playback controller.
   */
  private var mediaPlayer: MediaPlayer? = null

  /**
   * Initializes the controller.
   */
  init {
    Log.d(TAG, "Constructor called")

    setWord(this.word)

    // When we click on the video, it should expand to a larger preview.
    videoView.setOnClickListener {
      val bundle = Bundle()
      bundle.putString("prompt", this.word)
      bundle.putString("filepath", "tutorial" + File.separator + "${this.word}.mp4")
      bundle.putBoolean("landscape", true)

      val previewFragment = VideoPreviewFragment(R.layout.recording_preview)
      previewFragment.arguments = bundle

      previewFragment.show(activity.supportFragmentManager, "videoPreview")
    }
  }

  /**
   * Allows us to control the visibility of the video player directly from [RecordingActivity].
   */
  fun setVisibility(visibility: Int) {
    videoView.visibility = visibility
  }

  /**
   * Sets the video player to play the given word.
   */
  fun setWord(newWord: String) {
    word = newWord
    videoView.visibility = View.VISIBLE

    // If the media player already exists, just change its data source and start playback again.
    mediaPlayer?.let {
      it.stop()
      it.reset()
      it.setDataSource(dataSource!!)
      it.setOnPreparedListener(this@VideoTutorialController)
      it.prepareAsync()
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
  }


  /**
   * When the videoView's pixel buffer is set up, this function is called. The MediaPlayer object
   * will then target the videoView's canvas.
   */
  override fun surfaceCreated(holder: SurfaceHolder) {
    this.mediaPlayer = MediaPlayer().apply {
      dataSource?.let {
        setDataSource(dataSource!!)
        setSurface(holder.surface)
        setOnPreparedListener(this@VideoTutorialController)
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
    this.mediaPlayer?.let {
      it.stop()
      it.release()
    }
  }

  /**
   * This function is called since we used `setOnPreparedListener(this@VideoTutorialController)`.
   * When the video file is ready, this function makes it so that the video loops and then starts
   * playing back.
   */
  override fun onPrepared(mp: MediaPlayer?) {
    mp?.let {
      it.isLooping = true
      it.start()
    }
  }

}
