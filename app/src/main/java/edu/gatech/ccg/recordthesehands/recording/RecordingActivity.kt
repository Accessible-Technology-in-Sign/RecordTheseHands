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

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.CycleInterpolator
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.common.util.concurrent.ListenableFuture
import edu.gatech.ccg.recordthesehands.Constants.COUNTDOWN_DURATION
import edu.gatech.ccg.recordthesehands.Constants.DEFAULT_SESSION_LENGTH
import edu.gatech.ccg.recordthesehands.Constants.DEFAULT_TUTORIAL_SESSION_LENGTH
import edu.gatech.ccg.recordthesehands.Constants.RESULT_ACTIVITY_STOPPED
import edu.gatech.ccg.recordthesehands.Constants.TABLET_SIZE_THRESHOLD_INCHES
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_NOTIFICATION_ID
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_RESUME_ON_IDLE_TIMEOUT
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_RESUME_ON_STOP_RECORDING_TIMEOUT
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.databinding.ActivityRecordBinding
import edu.gatech.ccg.recordthesehands.padZeroes
import edu.gatech.ccg.recordthesehands.sendEmail
import edu.gatech.ccg.recordthesehands.toHex
import edu.gatech.ccg.recordthesehands.upload.DataManager
import edu.gatech.ccg.recordthesehands.upload.Prompt
import edu.gatech.ccg.recordthesehands.upload.Prompts
import edu.gatech.ccg.recordthesehands.upload.UploadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.Integer.min
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.sqrt
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
  val clipId: String,
  val sessionId: String,
  val filename: String,
  val prompt: Prompt,
  val videoStart: Instant,
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
  addKeyValue("clipData-${clipDetails.clipId}", json, "clip")
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
  val sessionType: String, val initialPromptIndex: Int, val limitPromptIndex: Int
) {
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
  addKeyValue("sessionData-${sessionInfo.sessionId}", json, "session")
}

/**
 * This class handles the recording of ASL into videos.
 *
 * @author  Matthew So <matthew.so@gatech.edu>, Sahir Shahryar <contact@sahirshahryar.com>
 * @since   October 4, 2021
 * @version 1.1.0
 */
class RecordingActivity : AppCompatActivity(), WordPromptFragment.PromptDisplayModeListener {
  companion object {
    private val TAG = RecordingActivity::class.java.simpleName
  }


  // UI elements
  /**
   * Big red button used to start/stop a clip. (Note that we are continuously recording;
   * the button only marks when the user started or stopped signing to the camera.)
   *
   * Note that this button can be either a FloatingActionButton or a Button, depending on
   * whether we are on a smartphone or a tablet, respectively.
   */
  private lateinit var binding: ActivityRecordBinding

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
   * The current prompt index within the active section. This is updated in memory during the
   * session and saved to disk only at the end.
   */
  private var currentPromptIndex: Int = 0

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

  /**
   * Whether to use the summary page.
   */
  var useSummaryPage = false

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

  // CameraX variables
  private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
  private lateinit var cameraSelector: CameraSelector
  private var preview: Preview? = null
  private var videoCapture: VideoCapture<Recorder>? = null
  private var recording: Recording? = null
  private lateinit var cameraExecutor: ExecutorService

  /**
   * Changing camera preview dynamically relies on programmatically changing constraints. We keep a
   * copy of the original constraint to ease resetting our constraint back to its original form.
   */
  private lateinit var origAspectRatioLayout: ConstraintSet

  /**
   * Window insets controller for hiding and showing the toolbars.
   */
  var windowInsetsController: WindowInsetsControllerCompat? = null


  // Permissions
  /**
   * Marks whether the user has enabled the necessary permissions to record successfully. If
   * we don't check this, the app will crash instead of presenting an error.
   */
  private var permissions: Boolean = true

  /**
   * When the activity starts, this routine checks the CAMERA
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
   * Experimental values for portrait, landscape, tablet, non-tablet camera preview scaling.
   */
  private val originalPortraitWidthScaleFactor = 0.85f

  private val originalLandscapeWidthScaleFactor = 0.5f

  private val splitLandscapeWidthScaleFactor = 0.5f

  private val splitPortraitHeightScaleFactor = 0.5f

  private fun startCamera() {
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    cameraProviderFuture = ProcessCameraProvider.getInstance(this)
    cameraProviderFuture.addListener({
      val cameraProvider = cameraProviderFuture.get()

      preview = Preview.Builder().build().also {
        it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
      }

      val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
        .build()
      videoCapture = VideoCapture.withOutput(recorder)

      cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

      try {
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
          this, cameraSelector, preview, videoCapture
        )
      } catch (exc: Exception) {
        Log.e(TAG, "Use case binding failed", exc)
      }
    }, ContextCompat.getMainExecutor(this))

    // TODO analyze where the pause Upload Timeout is being set.
    UploadService.pauseUploadTimeout(COUNTDOWN_DURATION + UPLOAD_RESUME_ON_IDLE_TIMEOUT)

    // Set up the countdown timer.
    binding.timerLabel.text = "00:00"
    countdownTimer = object : CountDownTimer(COUNTDOWN_DURATION, 1000) {
      // Update the timer text every second.
      override fun onTick(p0: Long) {
        val rawSeconds = (p0 / 1000).toInt() + 1
        val minutes = padZeroes(rawSeconds / 60, 2)
        val seconds = padZeroes(rawSeconds % 60, 2)
        binding.timerLabel.text = "$minutes:$seconds"
      }

      // When the timer expires, move to the summary page (or have the app move there as soon
      // as the user finishes the recording they're currently working on).
      override fun onFinish() {
        if (isSigning) {
          // TODO test this.
          endSessionOnClipEnd = true
        } else {
          // TODO test this.
          goToSummaryPage()
        }
      }
    } // CountDownTimer

    countdownTimer.start()
  }

  private fun startRecording() {
    val videoCapture = this.videoCapture ?: return

    val mediaStoreOutputOptions = MediaStoreOutputOptions
      .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
      .setContentValues(ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
      })
      .build()

    recording = videoCapture.output
      .prepareRecording(this, mediaStoreOutputOptions)
      .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
        when (recordEvent) {
          is VideoRecordEvent.Start -> {
            // Handle recording start
          }

          is VideoRecordEvent.Finalize -> {
            if (!recordEvent.hasError()) {
              val msg = "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
              Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
              Log.d(TAG, msg)
            } else {
              recording?.close()
              recording = null
              Log.e(TAG, "Video capture ends with error: ${recordEvent.error}")
            }
          }
        }
      }
  }

  private fun stopRecording() {
    recording?.stop()
    recording = null
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
      MotionEvent.ACTION_DOWN -> {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        startRecording()
        val now = Instant.now()
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
        dataManager.logToServerAtTimestamp(timestamp, "recordButton down")
        currentClipDetails =
          ClipDetails(
            newClipId(), sessionInfo.sessionId, filename,
            prompts.array[sessionStartIndex + currentPage], sessionStartTime
          )
        currentClipDetails!!.startButtonDownTimestamp = now
        currentClipDetails!!.lastModifiedTimestamp = now
        clipData.add(currentClipDetails!!)
        CoroutineScope(Dispatchers.IO).launch {
          dataManager.saveClipData(currentClipDetails!!)
        }

        isSigning = true
        runOnUiThread {
          animateGoText()
        }
      }

      MotionEvent.ACTION_UP -> {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
        stopRecording()
        val now = Instant.now()
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
        dataManager.logToServerAtTimestamp(timestamp, "recordButton up")
        if (currentClipDetails != null) {
          currentClipDetails!!.startButtonUpTimestamp = now
          currentClipDetails!!.lastModifiedTimestamp = now
          CoroutineScope(Dispatchers.IO).launch {
            dataManager.saveClipData(currentClipDetails!!)
          }
        }
        runOnUiThread {
          setButtonState(binding.recordButton, false)
          setButtonState(binding.restartButton, true)
          setButtonState(binding.finishedButton, false)
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
          ClipDetails(
            newClipId(), sessionInfo.sessionId,
            filename, prompts.array[sessionStartIndex + currentPage], sessionStartTime
          )
        currentClipDetails!!.startButtonDownTimestamp = now
        currentClipDetails!!.lastModifiedTimestamp = now
        clipData.add(currentClipDetails!!)
        dataManager.saveClipData(currentClipDetails!!)

        isSigning = true
        runOnUiThread {
          setButtonState(binding.recordButton, false)
          setButtonState(binding.restartButton, true)
          setButtonState(binding.finishedButton, false)
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
    isSigning = false

    if (!useSummaryPage) {
      concludeRecordingSession()
    }
    runOnUiThread {
      // Move to the next prompt and allow the user to swipe back and forth.
      binding.sessionPager.setCurrentItem(sessionLimit - sessionStartIndex + 1, false)
      binding.sessionPager.isUserInputEnabled = false
    }
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
    stopRecording()
    setResult(RESULT_ACTIVITY_STOPPED)
    dataManager.logToServer("onRestart called.")
    finish()
  }

  private fun resetConstraintLayout() {
    origAspectRatioLayout.applyTo(binding.aspectRatioConstraint)
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
        stopRecording()
        setResult(RESULT_ACTIVITY_STOPPED)
      }
      cameraExecutor.shutdown()
      /**
       * This is remnant code from when we were attempting to find and fix a memory leak
       * that occurred if the user did too many recording sessions in one sitting. It is
       * unsure whether this helped; however, we will leave it as-is for now.
       */
      binding.sessionPager.adapter = null
      super.onStop()
    } catch (exc: Throwable) {
      Log.e(TAG, "Error in RecordingActivity.onStop()", exc)
    }
    UploadService.pauseUploadTimeout(UPLOAD_RESUME_ON_STOP_RECORDING_TIMEOUT)
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

  private fun saveRecordingData() {
    CoroutineScope(Dispatchers.IO).launch {
      val now = Instant.now()
      val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
      val json = JSONObject()
      json.put("filename", filename)
      json.put("endTimestamp", timestamp)
      dataManager.addKeyValue("recording_stopped-${timestamp}", json, "recording")
      dataManager.registerFile(outputFile.relativeTo(applicationContext.filesDir).path)
      sessionInfo.endTimestamp = now
      sessionInfo.finalPromptIndex = currentPromptIndex
      dataManager.saveCurrentPromptIndex(currentPromptIndex)
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

    isTablet = diagonal > TABLET_SIZE_THRESHOLD_INCHES
    binding = ActivityRecordBinding.inflate(this.layoutInflater)

    origAspectRatioLayout = ConstraintSet().apply {
      clone(binding.aspectRatioConstraint)
    }

    setContentView(binding.root)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    cameraExecutor = Executors.newSingleThreadExecutor()

    // Fetch word data, user id, etc. from the splash screen activity which
    // initiated this activity
    val bundle = this.intent.extras ?: Bundle()

    emailConfirmationEnabled = bundle.getBoolean("SEND_CONFIRMATION_EMAIL")

    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

    val initialState = dataManager.promptState.value
    if (initialState == null) {
      throw IllegalStateException("Prompt state not available.")
    }
    val tmpCurrentPrompts = initialState.currentPrompts
    val tmpCurrentPromptIndex = initialState.currentPromptIndex
    if (tmpCurrentPrompts == null || tmpCurrentPromptIndex == null) {
      throw IllegalStateException("Prompt state not available.")
    }
    this.prompts = tmpCurrentPrompts
    this.sessionStartIndex = tmpCurrentPromptIndex
    this.currentPromptIndex = tmpCurrentPromptIndex
    this.useSummaryPage =
      initialState.promptsCollection?.sections?.get(initialState.currentSectionName)?.metadata?.useSummaryPage
        ?: false
    username = initialState.username ?: throw IllegalStateException("username not available.")
    tutorialMode = initialState.tutorialMode
    val sessionType = if (tutorialMode) "tutorial" else "normal"
    val sessionId = runBlocking { dataManager.newSessionId() }
    if (tutorialMode) {
      filename = "tutorial-${username}-${sessionId}-${timestamp}.mp4"
    } else {
      filename = "${username}-${sessionId}-${timestamp}.mp4"
    }
    val sessionLength =
      if (tutorialMode) DEFAULT_TUTORIAL_SESSION_LENGTH else DEFAULT_SESSION_LENGTH
    sessionLimit = min(prompts.array.size, sessionStartIndex + sessionLength)
    sessionInfo = RecordingSessionInfo(
      sessionId, filename, runBlocking { dataManager.getDeviceId() }, username, sessionType,
      sessionStartIndex, sessionLimit
    )
    runBlocking { dataManager.saveSessionInfo(sessionInfo) }

    dataManager.logToServer(
      "Setting up recording with filename ${filename} for prompts " +
          "[${sessionStartIndex}, ${sessionLimit})"
    )

    // Set title bar text
    title = "${currentPromptIndex + 1} of ${prompts.array.size}"

    // Enable record button
    binding.recordButton.isHapticFeedbackEnabled = true
    setButtonState(binding.recordButton, true)

    binding.restartButton.isHapticFeedbackEnabled = true
    setButtonState(binding.restartButton, false)

    binding.finishedButton.isHapticFeedbackEnabled = true
    setButtonState(binding.finishedButton, false)

    if (!isTablet) {
      scaleRecordButton(binding.recordButton as Button)
      scaleRecordButton(binding.restartButton as Button)
      scaleRecordButton(binding.finishedButton as Button)
    }

    // Instantiate recording indicator
    binding.recordingLight.visibility = View.GONE

    binding.sessionPager.adapter = WordPagerAdapter(this, useSummaryPage)

    // Set up swipe handler for the word selector UI
    binding.sessionPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
      /**
       * Page changed
       */
      override fun onPageSelected(position: Int) {
        Log.d(
          TAG,
          "onPageSelected(${position}) sessionPager.currentItem ${binding.sessionPager.currentItem} currentPage (before updating) ${currentPage}"
        )
        if (currentClipDetails != null) {
          val now = Instant.now()
          DateTimeFormatter.ISO_INSTANT.format(now)
          if (currentPage < binding.sessionPager.currentItem) {
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
        currentPage = binding.sessionPager.currentItem
        super.onPageSelected(currentPage)
        if (endSessionOnClipEnd) {
          currentPromptIndex += 1
          goToSummaryPage()
          return
        }
        val promptIndex = sessionStartIndex + currentPage

        if (promptIndex < sessionLimit) {
          dataManager.logToServer("selected page for promptIndex ${promptIndex}")
          currentPromptIndex = promptIndex
          runOnUiThread {
            title = "${currentPromptIndex + 1} of ${prompts.array.size}"

            setButtonState(binding.recordButton, true)
            setButtonState(binding.restartButton, false)
            setButtonState(binding.finishedButton, false)
            binding.recordingLight.visibility = View.VISIBLE
          }
        } else if (promptIndex == sessionLimit) {
          dataManager.logToServer("selected last chance page (promptIndex ${promptIndex})")
          currentPromptIndex = promptIndex
          /**
           * Page to give the user a chance to swipe back and record more before
           * finishing.
           */
          title = ""

          setButtonState(binding.recordButton, false)
          setButtonState(binding.restartButton, false)
          setButtonState(binding.finishedButton, true)
          binding.recordingLight.visibility = View.GONE
        } else {
          dataManager.logToServer("selected corrections page (promptIndex ${promptIndex})")
          if (!useSummaryPage) {
            // Shouldn't happen, but just in case.
            concludeRecordingSession()
          }
          title = ""

          setButtonState(binding.recordButton, false)
          setButtonState(binding.restartButton, false)
          setButtonState(binding.finishedButton, false)
          binding.sessionPager.isUserInputEnabled = false

          sessionInfo.result = "ON_CORRECTIONS_PAGE"
          stopRecording()
          UploadService.pauseUploadTimeout(UPLOAD_RESUME_ON_IDLE_TIMEOUT)
        }
      }
    })

    // TODO clone from the original constraints.
    val aspectRatioParams = binding.aspectRatioConstraint.layoutParams as LayoutParams
    val currentOrientation = resources.configuration.orientation

    val screenWidth = resources.displayMetrics.widthPixels
    val screenHeight = resources.displayMetrics.heightPixels

    val density = resources.displayMetrics.density
    val widthDp = screenWidth / density
    screenHeight / density

    val desiredOriginalPortraitWidthPx =
      calculateScaledPixelWidth(widthDp, originalPortraitWidthScaleFactor)
    val desiredOriginalLandscapeWidthPx =
      calculateScaledPixelWidth(widthDp, originalLandscapeWidthScaleFactor)

    setOriginalScreen(
      binding.aspectRatioConstraint,
      aspectRatioParams,
      currentOrientation,
      desiredOriginalPortraitWidthPx,
      desiredOriginalLandscapeWidthPx
    )

    startCamera()
  }

  /**
   * Takes a [PromptDisplayMode] enum value from [WordPromptFragment] and adjusts camera preview
   * dynamically through implementation of [WordPromptFragment.PromptDisplayModeListener] interface
   * as a callback.
   */
  override fun displayModeListener(displayMode: WordPromptFragment.PromptDisplayMode?) {
    val aspectRatioParams = binding.aspectRatioConstraint.layoutParams as LayoutParams

    // Detects screen's orientation as portrait or landscape
    val currentOrientation = resources.configuration.orientation

    // These values depend on screen orientation
    val screenWidth = resources.displayMetrics.widthPixels
    val screenHeight = resources.displayMetrics.heightPixels

    val density = resources.displayMetrics.density
    val widthDp = screenWidth / density
    val heightDp = screenHeight / density

    val desiredOriginalPortraitWidthPx =
      calculateScaledPixelWidth(widthDp, originalPortraitWidthScaleFactor)
    val desiredOriginalLandscapeWidthPx =
      calculateScaledPixelWidth(widthDp, originalLandscapeWidthScaleFactor)
    val desiredSplitLandscapeWidthPx =
      calculateScaledPixelWidth(widthDp, splitLandscapeWidthScaleFactor)
    val desiredSplitPortraitHeightPx =
      calculateScaledPixelWidth(heightDp, splitPortraitHeightScaleFactor)

    when (displayMode) {
      // Handles full-screening the camera preview
      WordPromptFragment.PromptDisplayMode.FULL -> {
        resetConstraintLayout()
        // Same logic for both phone and tablet
        setFullScreen(binding.aspectRatioConstraint, aspectRatioParams)
      }

      // Handles split-screening the camera preview
      WordPromptFragment.PromptDisplayMode.SPLIT -> {
        resetConstraintLayout()
        setSplitScreen(
          binding.aspectRatioConstraint,
          aspectRatioParams,
          currentOrientation,
          desiredSplitPortraitHeightPx,
          desiredSplitLandscapeWidthPx
        )
      }

      // Handles minimizing the camera preview to original size
      WordPromptFragment.PromptDisplayMode.ORIGINAL -> {
        resetConstraintLayout()
        setOriginalScreen(
          binding.aspectRatioConstraint,
          aspectRatioParams,
          currentOrientation,
          desiredOriginalPortraitWidthPx,
          desiredOriginalLandscapeWidthPx
        )
      }

      else -> {
        throw IllegalStateException("Unknown display mode $displayMode")
      }
    }
  }

  /**
   * Sets the camera preview to default screen mode.
   */
  private fun setOriginalScreen(
    aspectRatioLayout: View,
    aspectRatioParams: LayoutParams,
    currentOrientation: Int,
    desiredOriginalPortraitWidthPx: Int,
    desiredOriginalLandscapeWidthPx: Int
  ) {
    aspectRatioLayout.visibility = View.VISIBLE
    // binding.recordingLight.visibility = View.GONE
    // aspectRatioLayout.layoutParams = aspectRatioParams
    Log.d(TAG, "setOriginalScreen")
  }

  /**
   * Sets the camera preview to split screen mode.
   */
  private fun setSplitScreen(
    aspectRatioLayout: View,
    aspectRatioParams: LayoutParams,
    currentOrientation: Int,
    desiredSplitPortraitHeightPx: Int,
    desiredSplitLandscapeWidthPx: Int
  ) {
    Log.d(TAG, "setSplitScreen")
    aspectRatioLayout.visibility = View.VISIBLE
    if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
      if (isTablet) {
        aspectRatioParams.height = desiredSplitPortraitHeightPx
        aspectRatioParams.width = (aspectRatioParams.height * (3f / 4f)).toInt()
        aspectRatioParams.topMargin = 900
        aspectRatioParams.marginStart = 0
        aspectRatioParams.bottomMargin = 0
        aspectRatioParams.startToStart = ViewGroup.LayoutParams.MATCH_PARENT
        aspectRatioParams.endToEnd = R.id.sessionPager
        Log.i(TAG, "Configuring Layout in Tablet Mode")
      } else {
        aspectRatioParams.height = desiredSplitPortraitHeightPx
        aspectRatioParams.width = (aspectRatioParams.height * (3f / 4f)).toInt()
        aspectRatioParams.topMargin = 500
        aspectRatioParams.marginStart = 0
        aspectRatioParams.bottomMargin = 0
        aspectRatioParams.startToStart = ViewGroup.LayoutParams.MATCH_PARENT
        aspectRatioParams.endToEnd = R.id.sessionPager
        Log.i(TAG, "Configuring Layout in Phone Mode")
      }
      Log.i(TAG, "In split portrait")
    } else if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
      if (isTablet) {
        aspectRatioParams.width = desiredSplitLandscapeWidthPx
        aspectRatioParams.height = (aspectRatioParams.width * (3f / 4f)).toInt()
        aspectRatioParams.topMargin = 100
        aspectRatioParams.marginStart = 0
        aspectRatioParams.bottomMargin = 100
        aspectRatioParams.startToStart = ViewGroup.LayoutParams.MATCH_PARENT
        aspectRatioParams.endToEnd = LayoutParams.UNSET
      } else {
        aspectRatioParams.width = desiredSplitLandscapeWidthPx
        aspectRatioParams.height = (aspectRatioParams.width * (3f / 4f)).toInt()
        aspectRatioParams.topMargin = 100
        aspectRatioParams.marginStart = 0
        aspectRatioParams.bottomMargin = 100
        aspectRatioParams.startToStart = ViewGroup.LayoutParams.MATCH_PARENT
        aspectRatioParams.endToEnd = LayoutParams.UNSET
      }
      Log.i(TAG, "In split landscape")
    }
    aspectRatioLayout.layoutParams = aspectRatioParams
//    binding.recordingLight.visibility = View.VISIBLE
    binding.recordingLight.visibility = View.GONE
  }

  /**
   * Sets the camera preview to full screen mode.
   */
  private fun setFullScreen(
    aspectRatioLayout: View,
    aspectRatioParams: LayoutParams
  ) {
    Log.d(TAG, "setFullScreen")
    aspectRatioLayout.visibility = View.GONE
    // binding.recordingLight.visibility = View.GONE
    // aspectRatioLayout.layoutParams = aspectRatioParams
  }

  /**
   * Function to scale down the overly large record button on non-tablet devices.
   */
  private fun scaleRecordButton(button: Button) {
    // TODO Have a less hacky way of choosing between tablet and phone layouts.
    button.apply {
      scaleX = 1.25f
      scaleY = 1.25f

      val buttonParams = layoutParams as? LayoutParams
      buttonParams?.let {
        it.marginEnd = TypedValue.applyDimension(
          TypedValue.COMPLEX_UNIT_DIP,
          30f,
          resources.displayMetrics
        ).toInt()

        it.bottomMargin = TypedValue.applyDimension(
          TypedValue.COMPLEX_UNIT_DIP,
          15f,
          resources.displayMetrics
        ).toInt()

        this.layoutParams = it
      }

      setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }
  }

  /**
   * Calculates pixel width given a scaling factor and returns the dp integer value.
   */
  private fun calculateScaledPixelWidth(pixelWidthDensity: Float, scaleFactor: Float): Int {
    val scaledPixelDensityWidth = pixelWidthDensity * scaleFactor
    return TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      scaledPixelDensityWidth,
      resources.displayMetrics
    ).toInt()
  }

  private fun animateGoText() {
    binding.goText.visibility = View.VISIBLE

    // Set the pivot point for SCALE_X and SCALE_Y transformations to the
    // top-left corner of the zoomed-in view. The default is the center of
    // the view.
    //binding.expandedImage.pivotX = 0f
    //binding.expandedImage.pivotY = 0f

    // Construct and run the parallel animation of the four translation and
    // scale properties: X, Y, SCALE_X, and SCALE_Y.
    AnimatorSet().apply {
      play(
        ObjectAnimator.ofFloat(
          binding.goText,
          View.SCALE_X,
          .5f,
          2f
        )
      ).apply {
        with(
          ObjectAnimator.ofFloat(
            binding.goText,
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
          binding.goText.visibility = View.GONE
        }

        override fun onAnimationCancel(animation: Animator) {
          // currentAnimator = null
          binding.goText.visibility = View.GONE
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
  }


  /**
   * Finish the recording session and close the activity.
   */
  fun concludeRecordingSession() {
    sessionInfo.result = "RESULT_OK"
    stopRecording()
    setResult(RESULT_OK)
    countdownTimer.cancel()
    saveRecordingData()

    val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    val notification = dataManager.createNotification(
      "Recording Session Completed", "still need to upload"
    )
    notificationManager.notify(UPLOAD_NOTIFICATION_ID, notification)

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
    for (i in 0..clipData.size - 1) {
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
      try {
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
      } catch (e: android.content.res.Resources.NotFoundException) {
        Log.w(TAG, "Email credentials not found, skipping email confirmation.")
      }
    }
  }

} // RecordingActivity

