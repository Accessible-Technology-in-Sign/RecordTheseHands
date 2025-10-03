/**
 * This file is part of Record These Hands, licensed under the MIT license.
 *
 * Copyright (c) 2021-2025
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

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.util.Size
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import edu.gatech.ccg.recordthesehands.Constants.RECORDING_FRAMERATE
import edu.gatech.ccg.recordthesehands.Constants.RECORDING_HARD_STOP_DURATION
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.Integer.min
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread
import kotlin.math.max
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

  var startTimestamp: Instant? = null
  var endTimestamp: Instant? = null
  var endAction: String = "unknown"

  var valid = false
  var lastModifiedTimestamp: Instant? = null

  fun toJson(): JSONObject {
    val json = JSONObject()
    json.put("clipId", clipId)
    json.put("sessionId", sessionId)
    json.put("filename", filename)
    json.put("promptData", prompt.toJson())
    json.put("endAction", endAction)
    json.put("videoStart", DateTimeFormatter.ISO_INSTANT.format(videoStart))
    json.put("valid", valid)
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
    if (lastModifiedTimestamp != null) {
      json.put("lastModifiedTimestamp", lastModifiedTimestamp)
    }
    return json
  }

  /**
   * Creates a string representation for this recording.
   */
  override fun toString(): String {
    val json = toJson()
    return json.toString(2)
  }
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

/**
 * This class handles the recording of data collection videos.
 */
class RecordingActivity : FragmentActivity() {
  companion object {
    private val TAG = RecordingActivity::class.java.simpleName
  }

  private val viewModel: RecordingViewModel by viewModels()

  private val isConcluding = java.util.concurrent.atomic.AtomicBoolean(false)
  private val concludeLatch = java.util.concurrent.CountDownLatch(1)

  /**
   * Marks whether the user is using a tablet (diagonal screen size > 7.0 inches (~17.78 cm)).
   */
  private var isTablet = false

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

  /**
   * The prompts data.
   */
  lateinit var prompts: Prompts

  /**
   * The prompts data.
   */
  lateinit var promptsMetadata: PromptsSectionMetadata

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
   * Window insets controller for hiding and showing the toolbars.
   */
  var windowInsetsController: WindowInsetsControllerCompat? = null

  private fun startCamera() {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
    cameraProviderFuture.addListener({
      val cameraProvider = cameraProviderFuture.get()
      val resolutionSelector = ResolutionSelector.Builder()
        .setResolutionStrategy(
          ResolutionStrategy(
            Size(640, 480),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
          )
        )
        .build()
      preview = Preview.Builder()
        .setResolutionSelector(resolutionSelector)
        .build()

      val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
        .build()

      videoCapture = VideoCapture.Builder(recorder)
        .setTargetFrameRate(android.util.Range(RECORDING_FRAMERATE, RECORDING_FRAMERATE))
        .build()

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

    // TODO should the countdownTimer setup code be moved somewhere else?  It doesn't have anything
    // directly to do with the camera.

  }

  private fun setupCountdownTimer(onTick: (String) -> Unit) {
    // Set up the countdown timer.
    countdownTimer = object : CountDownTimer(COUNTDOWN_DURATION, 1000) {
      // Update the timer text every second.
      override fun onTick(p0: Long) {
        val rawSeconds = (p0 / 1000).toInt() + 1
        val minutes = padZeroes(rawSeconds / 60, 2)
        val seconds = padZeroes(rawSeconds % 60, 2)
        onTick("$minutes:$seconds")
      }

      // When the timer expires, wait for the current prompt to be done and then finish.
      // Or, if no prompt is being done, just finish immediately.
      override fun onFinish() {
        if (currentClipDetails != null) {
          endSessionOnClipEnd = true
        } else {
          concludeRecordingSession(RESULT_OK, "RESULT_OK_SESSION_REACHED_TIMER_LIMIT")
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

  private fun activateReadCountdownCircle(durationMs: Long) {
    viewModel.setReadCountdownDuration(durationMs.toInt())
    viewModel.setReadTimerActive(true)
  }

  private fun newClipId(): String {
    val output = "${sessionInfo.sessionId}-${padZeroes(clipIdIndex, 3)}"
    clipIdIndex += 1
    return output
  }

  private fun recordButtonOnClickListener() {
    lifecycleScope.launch(Dispatchers.IO) {
      val now = Instant.now()
      val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
      dataManager.logToServerAtTimestamp(timestamp, "recordButton clicked")
      val prompt = prompts.array[sessionStartIndex + currentPage]

      currentClipDetails =
        ClipDetails(
          newClipId(), sessionInfo.sessionId, filename,
          prompt, sessionStartTime
        ).also {
          it.startTimestamp = now
          it.valid = false
          it.lastModifiedTimestamp = now
          clipData.add(it)
          dataManager.saveClipData(it)
        }

      prompt.recordMinMs?.let {
        viewModel.setRecordingCountdownDuration(it)
        viewModel.restartRecordingCountdown()
      }

      runOnUiThread {
        viewModel.showGoText()
        viewModel.setButtonState(recordVisible = false, restartVisible = true)
      }
    }
  }

  private fun restartButtonOnClickListener() {
    lifecycleScope.launch(Dispatchers.IO) {
      val now = Instant.now()
      val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
      dataManager.logToServerAtTimestamp(timestamp, "restartButton clicked")
      currentClipDetails!!.let {
        it.endTimestamp = now
        it.endAction = "restart"
        it.valid = false
        it.lastModifiedTimestamp = now
        dataManager.saveClipData(it)
      }

      val prompt = prompts.array[sessionStartIndex + currentPage]
      currentClipDetails =
        ClipDetails(
          newClipId(), sessionInfo.sessionId,
          filename, prompt, sessionStartTime
        ).also {
          it.startTimestamp = now
          it.valid = false
          it.lastModifiedTimestamp = now
          clipData.add(it)
          dataManager.saveClipData(it)
        }

      prompt.recordMinMs?.let {
        viewModel.setRecordingCountdownDuration(it)
        viewModel.restartRecordingCountdown()
      }

      runOnUiThread {
        viewModel.showGoText()
      }
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
    concludeRecordingSession(RESULT_ACTIVITY_UNREACHABLE, "RESULT_UNREACHABLE_ON_RESTART")
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
    concludeRecordingSession(RESULT_OK, "RESULT_OK_ON_STOP")
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
    dataManager.launchSaveRecordingData(
      filename,
      outputFile,
      tutorialMode,
      sessionInfo,
      currentPromptIndex
    )
    if (emailConfirmationEnabled) {
      sendConfirmationEmail()
    }
  }

  /**
   * Entry point for the RecordingActivity.
   */
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    UploadService.pauseUploadTimeout(COUNTDOWN_DURATION + UPLOAD_RESUME_ON_IDLE_TIMEOUT)

    setContent {
      val timerText by viewModel.timerText.collectAsState()
      val recordButtonVisible by viewModel.recordButtonVisible.collectAsState()
      val restartButtonVisible by viewModel.restartButtonVisible.collectAsState()
      val isRecording by viewModel.isRecording.collectAsState()
      val goTextVisible by viewModel.goTextVisible.collectAsState()
      val isReadTimerActive by viewModel.isReadTimerActive.collectAsState()
      val readCountdownDuration by viewModel.readCountdownDuration.collectAsState()
      val isRecordingTimerActive by viewModel.isRecordingTimerActive.collectAsState()
      val recordingCountdownDuration by viewModel.recordingCountdownDuration.collectAsState()
      val recordingTimerKey by viewModel.recordingTimerKey.collectAsState()
      val viewedPrompts by viewModel.viewedPrompts.collectAsState()

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
        val (cameraPreview, pager, timerLabel, recordButtons, recordingLight, goText, backButton, readTimer) = createRefs()
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
          userScrollEnabled = !isReadTimerActive && !isRecordingTimerActive,
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
                onFinish = { concludeRecordingSession(RESULT_OK, "RESULT_OK_CONFIRM_PAGE") },
                modifier = commonModifier
              )
            } else {
              throw IllegalStateException("Unreachable page in HorizontalPager")
            }
          }
        }

        if (isReadTimerActive) {
          Box(
            modifier = Modifier
              .constrainAs(readTimer) {
                bottom.linkTo(parent.bottom)
                top.linkTo(guideline)
                end.linkTo(parent.end)
                start.linkTo(parent.start)
              }
              .clip(RoundedCornerShape(20.dp))
              .background(Color.Black.copy(alpha = 0.65f))
              .padding(30.dp),
            contentAlignment = Alignment.Center
          ) {
            Column(
              verticalArrangement = Arrangement.Center,
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Text(
                text = "Read and Prepare",
                color = Color.White,
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 10.dp, bottom = 30.dp)
              )
              CountdownCircle(
                durationMs = readCountdownDuration,
                componentSize = 200.dp,
                strokeWidthProportion = 0.3f,
                onFinished = {
                  viewModel.setReadTimerActive(false)
                }
              )
            }
          }
        }

        if (isRecordingTimerActive) {
          CountdownCircle(
            modifier = Modifier.constrainAs(readTimer) {
              top.linkTo(recordButtons.top)
              bottom.linkTo(recordButtons.bottom)
              start.linkTo(recordButtons.end)
              end.linkTo(parent.end)
            },
            componentSize = 50.dp,
            strokeWidthProportion = 0.1f,
            durationMs = recordingCountdownDuration,
            key = recordingTimerKey,
            onFinished = {
              viewModel.setRecordingTimerActive(false)
            }
          )
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
          modifier = Modifier
            .constrainAs(recordButtons) {
              bottom.linkTo(parent.bottom, margin = 60.dp)
              end.linkTo(parent.end, margin = 120.dp)
            },
          enabled = !isReadTimerActive,
        )
        BackButton(
          onClick = {
            dataManager.logToServer("User pressed back button to end recording.")
            concludeRecordingSession(RESULT_OK, "RESULT_OK_BACK_BUTTON")
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
          currentClipDetails?.let { clipDetails ->
            val now = Instant.now()
            DateTimeFormatter.ISO_INSTANT.format(now)
            if (currentPage < newPage) {
              // Swiped forward
              clipDetails.endTimestamp = now
              clipDetails.endAction = "swipe_forward"
            } else {
              // Swiped backwards
              clipDetails.endTimestamp = now
              clipDetails.endAction = "swipe_back"
            }
            clipDetails.valid = true
            clipDetails.lastModifiedTimestamp = now
            currentClipDetails = null
            val saveClipDataRoutine = CoroutineScope(Dispatchers.IO).launch {
              dataManager.saveClipData(clipDetails)
            }
            if (endSessionOnClipEnd) {
              currentPromptIndex += 1
              saveClipDataRoutine.join()  // the clip must be saved before ending the session.
              concludeRecordingSession(RESULT_OK, "RESULT_OK_ENDED_SESSION_ON_CLIP_END")
              return@LaunchedEffect
            }
          }
          currentPage = newPage
          val promptIndex = sessionStartIndex + currentPage
          currentPromptIndex = promptIndex

          if (promptIndex < sessionLimit) {
            dataManager.logToServer("selected page for promptIndex ${promptIndex}")
            title = "${currentPromptIndex + 1} of ${prompts.array.size}"
            viewModel.setButtonState(recordVisible = true, restartVisible = false)

            if (promptIndex !in viewedPrompts) {
              viewModel.markPromptAsViewed(promptIndex)
              val prompt = prompts.array[promptIndex]
              prompt.readMinMs?.let {
                activateReadCountdownCircle(it.toLong())
              }
            }
          } else if (promptIndex == sessionLimit) {
            dataManager.logToServer("selected confirm page (promptIndex ${promptIndex})")
            title = "Confirm Recording Session Finished"
            viewModel.setButtonState(recordVisible = false, restartVisible = false)
          }
        }
      }
    }

    windowInsetsController =
      WindowCompat.getInsetsController(window, window.decorView).also {
        it.systemBarsBehavior =
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }

    dataManager = DataManager.getInstance(applicationContext)

    // Calculate the display size to determine whether to use mobile or tablet layout.
    val displayMetrics = resources.displayMetrics
    val heightInches = displayMetrics.heightPixels / displayMetrics.ydpi
    val widthInches = displayMetrics.widthPixels / displayMetrics.xdpi
    val diagonal = sqrt((heightInches * heightInches) + (widthInches * widthInches))
    Log.i(TAG, "Computed screen size: $diagonal inches")

    isTablet = diagonal > TABLET_SIZE_THRESHOLD_INCHES

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

    startCamera()
    setupCountdownTimer {
      viewModel.onTick(it)
    }
    lifecycleScope.launch {
      delay(RECORDING_HARD_STOP_DURATION)
      dataManager.logToServer("Hard stop reached.")
      concludeRecordingSession(RESULT_ACTIVITY_STOPPED, "RESULT_ACTIVITY_STOPPED_HARD_STOP")
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
   */
  fun concludeRecordingSession(result: Int, resultString: String) {
    if (!isConcluding.compareAndSet(false, true)) {
      concludeLatch.await()
      return
    }
    UploadService.pauseUploadTimeout(UPLOAD_RESUME_ON_STOP_RECORDING_TIMEOUT)
    setResult(result)
    sessionInfo.result = resultString
    val now = Instant.now()
    sessionInfo.endTimestamp = now
    sessionInfo.finalPromptIndex = currentPromptIndex
    currentClipDetails?.let {
      it.endTimestamp = now
      it.endAction = "quit"
      it.valid = false
      it.lastModifiedTimestamp = now
      runBlocking {
        dataManager.saveClipData(it)
      }
      currentClipDetails = null
    }

    stopRecording()
    countdownTimer.cancel()
    saveRecordingData()

    val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    val notification = dataManager.createNotification(
      "Recording Session Concluded", "Upload will occur automatically."
    )
    notificationManager.notify(UPLOAD_NOTIFICATION_ID, notification)
    concludeLatch.countDown()
    finish()
  }

  /**
   * Handles the creation and sending of a confirmation email, allowing us to track
   * the user's progress.
   */
  private fun sendConfirmationEmail() {
    Log.d(TAG, "Sending Email confirmation")
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
        Log.d(TAG, "Email confirmation sent.")
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
    modifier: Modifier = Modifier,
    enabled: Boolean,
  ) {
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current

    SubcomposeLayout(modifier = modifier) { constraints ->
      // --- Measurement Phase ---
      // We subcompose both buttons to find out their dimensions.
      // The content is not placed, just measured.
      val recordMeasurables = subcompose("record") {
        PrimaryButton(onClick = {}, text = stringResource(R.string.record))
      }
      val restartMeasurables = subcompose("restart") {
        AlertButton(onClick = {}, text = stringResource(R.string.restart))
      }

      // Measure them to get their widths in pixels.
      val recordWidth =
        recordMeasurables.map { it.measure(constraints) }.maxOfOrNull { it.width } ?: 0
      val restartWidth =
        restartMeasurables.map { it.measure(constraints) }.maxOfOrNull { it.width } ?: 0
      val maxWidth = max(recordWidth, restartWidth)
      val maxWidthDp = with(density) { maxWidth.toDp() }

      // --- Layout Phase ---
      // Now we subcompose the actual content that will be displayed.
      val contentPlaceable = subcompose("content") {
        // The original Box and Column structure is preserved here.
        Box(
          contentAlignment = Alignment.BottomEnd
        ) {
          Column {
            val buttonModifier = Modifier.width(maxWidthDp)
            if (recordButtonVisible) {
              PrimaryButton(
                onClick = {
                  onRecordClick()
                  hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                },
                text = stringResource(R.string.record),
                enabled = enabled,
                grayOnDisabled = true,
                modifier = buttonModifier
              )
            }
            if (restartButtonVisible) {
              AlertButton(
                onClick = {
                  onRestartClick()
                  hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                },
                text = stringResource(R.string.restart),
                enabled = enabled,
                modifier = buttonModifier
              )
            }
          }
        }
      }[0].measure(constraints)

      // The size of the SubcomposeLayout will be the size of its content.
      layout(contentPlaceable.width, contentPlaceable.height) {
        // Place the content at (0, 0) within the SubcomposeLayout.
        contentPlaceable.placeRelative(0, 0)
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
