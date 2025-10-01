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
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInCirc
import androidx.compose.animation.core.EaseOutCirc
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.ListenableFuture
import edu.gatech.ccg.recordthesehands.Constants.COUNTDOWN_DURATION
import edu.gatech.ccg.recordthesehands.Constants.DEFAULT_SESSION_LENGTH
import edu.gatech.ccg.recordthesehands.Constants.DEFAULT_TUTORIAL_SESSION_LENGTH
import edu.gatech.ccg.recordthesehands.Constants.RESULT_ACTIVITY_FAILED
import edu.gatech.ccg.recordthesehands.Constants.RESULT_ACTIVITY_STOPPED
import edu.gatech.ccg.recordthesehands.Constants.RESULT_ACTIVITY_UNREACHABLE
import edu.gatech.ccg.recordthesehands.Constants.TABLET_SIZE_THRESHOLD_INCHES
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_NOTIFICATION_ID
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_RESUME_ON_IDLE_TIMEOUT
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_RESUME_ON_STOP_RECORDING_TIMEOUT
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.padZeroes
import edu.gatech.ccg.recordthesehands.sendEmail
import edu.gatech.ccg.recordthesehands.toHex
import edu.gatech.ccg.recordthesehands.ui.components.AlertButton
import edu.gatech.ccg.recordthesehands.ui.components.PrimaryButton
import edu.gatech.ccg.recordthesehands.upload.DataManager
import edu.gatech.ccg.recordthesehands.upload.Prompt
import edu.gatech.ccg.recordthesehands.upload.Prompts
import edu.gatech.ccg.recordthesehands.upload.PromptsSectionMetadata
import edu.gatech.ccg.recordthesehands.upload.UploadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.Integer.min
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
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
 * This class handles the recording of data collection videos.
 */
class RecordingActivity : FragmentActivity() {
  companion object {
    private val TAG = RecordingActivity::class.java.simpleName
  }

  private val viewModel: RecordingViewModel by viewModels()


  // UI state variables
  /**
   * Marks whether the user is using a tablet (diagonal screen size > 7.0 inches (~17.78 cm)).
   */
  private var isTablet = false

  /**
   * Marks whether or not the user is currently signing a word. This is essentially only true
   * for the duration that the user holds down the Record button.
   */
  private var isSigning = false

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
   * The prompts data.
   */
  lateinit var promptsMetadata: PromptsSectionMetadata

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
  private var preview by mutableStateOf<Preview?>(null)
  private var videoCapture: VideoCapture<Recorder>? = null
  private var recording: Recording? = null

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

  private fun startCamera(onTick: (String) -> Unit) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
    cameraProviderFuture.addListener({
      val cameraProvider = cameraProviderFuture.get()
      preview = Preview.Builder().build()

      val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
        .build()
      videoCapture = VideoCapture.withOutput(recorder)
      // TODO set the framerate.

      cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

      try {
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
          this, cameraSelector, preview, videoCapture
        )
        startRecording()
        viewModel.setRecordingState(true)
      } catch (exc: Exception) {
        Log.e(TAG, "Use case binding failed", exc)
      }
    }, ContextCompat.getMainExecutor(this))

    // TODO analyze where the pause Upload Timeout is being set.
    UploadService.pauseUploadTimeout(COUNTDOWN_DURATION + UPLOAD_RESUME_ON_IDLE_TIMEOUT)

    // TODO should the countdownTimer setup code be moved somewhere else?  It doesn't have anything
    // directly to do with the camera.

    // Set up the countdown timer.
    // binding.timerLabel.text = "00:00"
    countdownTimer = object : CountDownTimer(COUNTDOWN_DURATION, 1000) {
      // Update the timer text every second.
      override fun onTick(p0: Long) {
        val rawSeconds = (p0 / 1000).toInt() + 1
        val minutes = padZeroes(rawSeconds / 60, 2)
        val seconds = padZeroes(rawSeconds % 60, 2)
        onTick("$minutes:$seconds")
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
    if (viewModel.isRecording.value) {
      dataManager.logToServer("startRecording called when isRecording is true.")
      return
    }
    val videoCapture = this.videoCapture ?: return

    val fileOutputOptions = FileOutputOptions.Builder(outputFile).build()

    recording = videoCapture.output
      .prepareRecording(this, fileOutputOptions)
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
    viewModel.setRecordingState(false)
  }

  private fun newClipId(): String {
    val output = "${sessionInfo.sessionId}-${padZeroes(clipIdIndex, 3)}"
    clipIdIndex += 1
    return output
  }

  private fun recordButtonOnClickListener() {
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
      viewModel.showGoText()
      viewModel.setButtonState(recordVisible = false, restartVisible = true)
    }
  }

  private fun restartButtonOnClickListener() {
    lifecycleScope.launch(Dispatchers.IO) {
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
        viewModel.showGoText()
      }
    }
  }

  fun goToSummaryPage() {
    isSigning = false
    concludeRecordingSession(RESULT_OK, "RESULT_OK")
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
    // This should be completely unreachable.
    super.onRestart()
    stopRecording()
    dataManager.logToServer("onRestart called.")
    concludeRecordingSession(RESULT_ACTIVITY_UNREACHABLE, "ON_RESTART")
  }

  /**
   * Handles stopping the recording session.
   */
  override fun onStop() {
    super.onStop()
    // This activity cannot be restarted.  When it is stopped, it goes away completely.
    // This ensures that there is only ever one recording for each session and that the camera
    // does not record while the activity is in the background.
    windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
    Log.d(TAG, "Recording Activity: onStop")
    dataManager.logToServer("onStop called.")
    concludeRecordingSession(RESULT_ACTIVITY_STOPPED, "RESULT_ACTIVITY_STOPPED")
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
      UploadService.pauseUploadTimeout(UPLOAD_RESUME_ON_STOP_RECORDING_TIMEOUT)
      val now = Instant.now()
      val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
      val json = JSONObject()
      json.put("filename", filename)
      json.put("endTimestamp", timestamp)
      dataManager.addKeyValue("recording_stopped-${timestamp}", json, "recording")
      dataManager.registerFile(
        outputFile.relativeTo(applicationContext.filesDir).path,
        tutorialMode
      )
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
    Log.i(TAG, "saveRecordingData: finished")
  }

  /**
   * Entry point for the RecordingActivity.
   */
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      val timerText by viewModel.timerText.collectAsState()
      val recordButtonVisible by viewModel.recordButtonVisible.collectAsState()
      val restartButtonVisible by viewModel.restartButtonVisible.collectAsState()
      val isRecording by viewModel.isRecording.collectAsState()
      val goTextVisible by viewModel.goTextVisible.collectAsState()

      val lifecycleOwner = LocalLifecycleOwner.current
      val context = LocalContext.current
      val previewView = remember {
        PreviewView(context).apply {
          scaleType = PreviewView.ScaleType.FIT_CENTER
        }
      }
      val previewViewHolder = remember {
        FrameLayout(context)
      }

      var guidelineTargetPosition by remember { mutableStateOf(0f) }
      val guidelinePosition = remember { Animatable(0f) }
      val density = LocalDensity.current
      val coroutineScope = rememberCoroutineScope()

      LaunchedEffect(Unit) {
        // This effect uses a snapshotFlow to safely observe the preview state.
        // It will suspend until the preview is non-null, then set the surface
        // provider once. This is the robust way to handle the asynchronous
        // initialization of the camera preview.
        snapshotFlow { preview }
          .filterNotNull()
          .first().surfaceProvider = previewView.surfaceProvider
      }

      ConstraintLayout(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black)
      ) {
        val (cameraPreview, pager, timerLabel, recordButtons, recordingLight, goText, backButton) = createRefs()
        val guideline = createGuidelineFromTop(guidelinePosition.value.dp)

        CameraPreview(
          previewView,
          previewViewHolder,
          modifier = Modifier.constrainAs(cameraPreview) {
            top.linkTo(guideline)
            start.linkTo(parent.start)
            end.linkTo(parent.end)
            bottom.linkTo(parent.bottom)
            width = Dimension.fillToConstraints
            height = Dimension.fillToConstraints
          }
        )

        val pagerState = rememberPagerState(
          initialPage = 0,
          initialPageOffsetFraction = 0f
        ) {
          sessionLimit - sessionStartIndex + 1
        }
        HorizontalPager(
          state = pagerState,
          modifier = Modifier.constrainAs(pager) {
            top.linkTo(parent.top)
            start.linkTo(parent.start)
            end.linkTo(parent.end)
            bottom.linkTo(parent.bottom)
            width = Dimension.fillToConstraints
            height = Dimension.fillToConstraints
          }
        ) { page ->
          ConstraintLayout(modifier = Modifier.fillMaxSize()) {
            val (content) = createRefs()
            val commonModifier = Modifier
              .constrainAs(content) {
                top.linkTo(parent.top)
                start.linkTo(parent.start)
                end.linkTo(parent.end)
                width = Dimension.matchParent
                height = Dimension.wrapContent
              }
              .onGloballyPositioned { layoutCoordinates ->
                if (page == pagerState.currentPage) {
                  val yPositionInPixels =
                    layoutCoordinates.positionInRoot().y + layoutCoordinates.size.height
                  val newPosition = (yPositionInPixels / density.density)
                  if (guidelineTargetPosition != newPosition) {
                    val oldPosition = guidelineTargetPosition
                    guidelineTargetPosition = newPosition
                    coroutineScope.launch {
                      Log.d(
                        TAG,
                        "animating guideline position change from $oldPosition to $newPosition."
                      )
                      guidelinePosition.animateTo(
                        newPosition,
                        animationSpec = tween(200, easing = EaseOutCirc)
                      )
                    }
                  }
                }
              }

            if (page < sessionLimit - sessionStartIndex) {
              PromptView(
                prompt = prompts.array[sessionStartIndex + page],
                modifier = commonModifier
              )
            } else if (page == sessionLimit - sessionStartIndex) {
              ConfirmPage(
                onFinish = { goToSummaryPage() },
                modifier = commonModifier
              )
            } else {
              throw IllegalStateException("Unreachable page in HorizontalPager")
            }
          }
        }

        TimerLabel(
          text = timerText,
          modifier = Modifier.constrainAs(timerLabel) {
            bottom.linkTo(parent.bottom)
            start.linkTo(parent.start)
          }
        )
        RecordButtons(
          recordButtonVisible = recordButtonVisible,
          restartButtonVisible = restartButtonVisible,
          onRecordClick = {
            recordButtonOnClickListener()
          },
          onRestartClick = {
            restartButtonOnClickListener()
          },
          modifier = Modifier.constrainAs(recordButtons) {
            bottom.linkTo(parent.bottom)
            end.linkTo(parent.end)
          }
        )
        BackButton(
          onClick = {
            dataManager.logToServer("User pressed back button to end recording.")
            concludeRecordingSession(RESULT_OK, "RESULT_OK")
          },
          modifier = Modifier
            .constrainAs(backButton) {
              top.linkTo(guideline)
              start.linkTo(parent.start)
            }
            .padding(top = 0.dp, start = 16.dp)
        )
        RecordingLight(
          isRecording = isRecording,
          modifier = Modifier
            .constrainAs(recordingLight) {
              top.linkTo(guideline)
              end.linkTo(parent.end)
            }
            .padding(top = 0.dp, end = 16.dp)
        )
        GoText(
          visible = goTextVisible,
          onAnimationFinish = { viewModel.hideGoText() },
          modifier = Modifier.constrainAs(goText) {
            top.linkTo(parent.top)
            bottom.linkTo(parent.bottom)
            start.linkTo(parent.start)
            end.linkTo(parent.end)
          }
        )

        LaunchedEffect(pagerState.currentPage) {
          val newPage = pagerState.currentPage
          if (currentClipDetails != null) {
            val now = Instant.now()
            DateTimeFormatter.ISO_INSTANT.format(now)
            if (currentPage < newPage) {
              // Swiped forward
              currentClipDetails!!.swipeForwardTimestamp = now
            } else {
              // Swiped backwards
              currentClipDetails!!.swipeBackTimestamp = now
            }
            currentClipDetails!!.lastModifiedTimestamp = now
            val saveClipDetails = currentClipDetails!!
            currentClipDetails = null
            CoroutineScope(Dispatchers.IO).launch {
              dataManager.saveClipData(saveClipDetails)
            }
          }
          currentPage = newPage
          if (endSessionOnClipEnd) {
            currentPromptIndex += 1
            goToSummaryPage()
            return@LaunchedEffect
          }
          val promptIndex = sessionStartIndex + currentPage

          if (promptIndex < sessionLimit) {
            dataManager.logToServer("selected page for promptIndex ${promptIndex}")
            currentPromptIndex = promptIndex
            title = "${currentPromptIndex + 1} of ${prompts.array.size}"
            viewModel.setButtonState(recordVisible = true, restartVisible = false)
          } else if (promptIndex == sessionLimit) {
            dataManager.logToServer("selected confirm page (promptIndex ${promptIndex})")
            currentPromptIndex = promptIndex
            title = ""
            viewModel.setButtonState(recordVisible = false, restartVisible = false)
          }
        }
      }
    }

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
    // binding = ActivityRecordBinding.inflate(this.layoutInflater)

    // origAspectRatioLayout = ConstraintSet().apply {
    //   clone(binding.aspectRatioConstraint)
    // }

    // setContentView(binding.root)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
    val tmpMetadata = initialState.currentPromptsMetadata
    if (tmpMetadata == null) {
      throw IllegalStateException("Prompts Metadata not available.")
    }
    this.promptsMetadata = tmpMetadata
    this.sessionStartIndex = tmpCurrentPromptIndex
    this.currentPromptIndex = tmpCurrentPromptIndex
    username = initialState.username ?: throw IllegalStateException("username not available.")
    tutorialMode = initialState.tutorialMode
    val sessionType = if (tutorialMode) "tutorial" else "normal"
    val sessionId = runBlocking { dataManager.newSessionId() }
    if (tutorialMode) {
      filename = "tutorial-${sessionId}-${timestamp}.mp4"
    } else {
      filename = "${sessionId}-${timestamp}.mp4"
    }
    outputFile = filenameToFilepath(filename)
    val sessionLength =
      if (tutorialMode) DEFAULT_TUTORIAL_SESSION_LENGTH else DEFAULT_SESSION_LENGTH
    sessionLimit = min(prompts.array.size, sessionStartIndex + sessionLength)
    sessionInfo = RecordingSessionInfo(
      sessionId, filename, runBlocking { dataManager.getDeviceId() }, username, sessionType,
      sessionStartIndex, sessionLimit
    )
    sessionStartTime = Instant.now()
    sessionInfo.startTimestamp = sessionStartTime
    runBlocking { dataManager.saveSessionInfo(sessionInfo) }

    sessionInfo.result = "RESULT_FAILED"
    setResult(RESULT_ACTIVITY_FAILED)

    dataManager.logToServer(
      "Setting up recording with filename ${filename} for prompts " +
          "[${sessionStartIndex}, ${sessionLimit})"
    )

    // Set title bar text
    title = "${currentPromptIndex + 1} of ${prompts.array.size}"

    startCamera {
      viewModel.onTick(it)
    }
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
   * Handle activity resumption (typically from multitasking)
   */
  override fun onResume() {
    // It should only be possible for this function to be called once.
    super.onResume()
    windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
  }

  /**
   * Finish the recording session and close the activity.
   * Set sessionInfo.result and setResult() before calling this function.
   */
  fun concludeRecordingSession(result: Int? = null, resultString: String? = null) {
    if (result != null) {
      setResult(result)
    }
    if (resultString != null) {
      sessionInfo.result = resultString
    }

    stopRecording()
    countdownTimer.cancel()
    saveRecordingData()

    val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    val notification = dataManager.createNotification(
      "Recording Session Concluded", "Upload will occur automatically."
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


  @Composable
  fun RecordingLight(isRecording: Boolean, modifier: Modifier = Modifier) {
    if (isRecording) {
      Box(
        modifier = modifier,
        contentAlignment = Alignment.TopEnd
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = stringResource(R.string.recording),
            color = Color.White,
            modifier = Modifier.padding(end = 10.dp)
          )
          Box(
            modifier = Modifier
              .size(20.dp)
              .background(color = Color(0xFFFF160C), shape = CircleShape)
          )
        }
      }
    }
  }

  @Composable
  fun RecordButtons(
    recordButtonVisible: Boolean,
    restartButtonVisible: Boolean,
    onRecordClick: () -> Unit,
    onRestartClick: () -> Unit,
    modifier: Modifier = Modifier
  ) {
    val hapticFeedback = LocalHapticFeedback.current
    val view = LocalView.current

    Box(
      modifier = modifier
        .fillMaxSize()
        .padding(bottom = 60.dp, end = 120.dp),
      contentAlignment = Alignment.BottomEnd
    ) {
      Column {
        if (recordButtonVisible) {
          PrimaryButton(
            onClick = {
              onRecordClick()
              hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
            },
            text = stringResource(R.string.record),
          )
        }
        if (restartButtonVisible) {
          AlertButton(
            onClick = {
              onRestartClick()
              hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
            },
            text = stringResource(R.string.restart),
          )
        }
      }
    }
  }


  @Composable
  fun TimerLabel(text: String, modifier: Modifier = Modifier) {
    Box(
      modifier = modifier
        .fillMaxSize()
        .padding(15.dp),
      contentAlignment = Alignment.BottomStart
    ) {
      Text(
        text = text,
        color = Color.White,
        fontSize = 20.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
          .clip(RoundedCornerShape(10.dp))
          .background(Color.Black.copy(alpha = 0.5f))
          .padding(10.dp)
      )
    }
  }

  @Composable
  fun GoText(visible: Boolean, onAnimationFinish: () -> Unit, modifier: Modifier = Modifier) {
    if (visible) {
      val scale = remember { Animatable(0f) }
      LaunchedEffect(Unit) {
        scale.animateTo(3f, animationSpec = tween(400, easing = EaseOutCirc))
        scale.animateTo(0f, animationSpec = tween(400, easing = EaseInCirc))
        onAnimationFinish()
      }
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
      ) {
        Box(
          modifier = Modifier
            .scale(scale.value)
            .background(Color.White, shape = RoundedCornerShape(16.dp))
        ) {
          Text(
            text = stringResource(R.string.go),
            color = Color.Black,
            fontSize = 50.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
          )
        }
      }
    }
  }

  @Composable
  fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
      modifier = modifier,
      contentAlignment = Alignment.TopStart
    ) {
      IconButton(onClick = onClick) {
        Icon(
          painter = painterResource(id = R.drawable.back_arrow),
          contentDescription = stringResource(R.string.back_button),
          tint = Color.White
        )
      }
    }
  }

  @Composable
  fun CameraPreview(
    previewView: PreviewView,
    holder: FrameLayout,
    modifier: Modifier = Modifier
  ) {
    AndroidView(
      factory = {
        holder.apply {
          layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
          addView(previewView)
        }
      },
      modifier = modifier
    )
  }
}

@Composable
fun ConfirmPage(onFinish: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier,
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp)
        .border(3.dp, Color.Black, shape = RoundedCornerShape(8.dp))
        .background(Color.White, shape = RoundedCornerShape(8.dp))
        .padding(horizontal = 0.dp, vertical = 40.dp),
      contentAlignment = Alignment.Center
    ) {
      Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "End of Prompts",
          fontSize = 28.sp,
          fontWeight = FontWeight.Bold,
          color = Color.Black
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryButton(
          onClick = onFinish,
          text = stringResource(id = R.string.finish)
        )
      }
    }
  }
}

@Composable
fun PromptView(prompt: Prompt, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .padding(12.dp)
      .border(3.dp, Color.Black, shape = RoundedCornerShape(8.dp))
      .background(Color.White, shape = RoundedCornerShape(8.dp))
      .padding(12.dp)
  ) {
    Text(
      text = prompt.prompt ?: "",
      color = Color.Black,
      fontSize = 30.sp
    )
  }
}
