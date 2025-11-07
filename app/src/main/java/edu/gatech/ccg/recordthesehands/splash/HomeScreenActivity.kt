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
package edu.gatech.ccg.recordthesehands.splash

import android.Manifest.permission.CAMERA
import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.lifecycle.lifecycleScope
import edu.gatech.ccg.recordthesehands.Constants
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_RESUME_ON_ACTIVITY_FINISHED
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_RESUME_ON_IDLE_TIMEOUT
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.recording.RecordingActivity
import edu.gatech.ccg.recordthesehands.thisDeviceIsATablet
import edu.gatech.ccg.recordthesehands.ui.components.PrimaryButton
import edu.gatech.ccg.recordthesehands.ui.components.SecondaryButton
import edu.gatech.ccg.recordthesehands.upload.DataManager
import edu.gatech.ccg.recordthesehands.upload.InterruptedUploadException
import edu.gatech.ccg.recordthesehands.upload.ServerStatus
import edu.gatech.ccg.recordthesehands.upload.UploadPauseManager
import edu.gatech.ccg.recordthesehands.upload.UploadState
import edu.gatech.ccg.recordthesehands.upload.UploadStatus
import edu.gatech.ccg.recordthesehands.upload.UploadWorkManager
import edu.gatech.ccg.recordthesehands.upload.prefStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * The home page for the app. The user can see statistics and start recording from this page.
 */
class HomeScreenActivity : ComponentActivity() {


  private var windowInsetsController: WindowInsetsControllerCompat? = null

  companion object {
    private val TAG = HomeScreenActivity::class.simpleName
  }

  // State elements

  /**
   * The DataManager object for mediating with the backend server and storing various values.
   */
  private lateinit var dataManager: DataManager

  /**
   * Whether or not emailing confirmation emails is enabled. If any of the
   * required configuration values (sender email, sender email's password,
   * recipient emails) are not set, we cannot send emails and so this function
   * should be disabled.
   */
  private var emailing: Boolean = true

  /**
   * Whether or not we have already asked the user for permissions.
   */
  private var permissionRequestedPreviously: Boolean = false

  /**
   * The total number of recordings the user has done in the current session (i.e., since they
   * last cold-booted the app). After this value reaches [Constants.MAX_RECORDINGS_IN_SITTING],
   * we ask the user to fully close and relaunch the app. This is in place as a quick-hack
   * solution for occasional memory leaks and crashes that occurred when the app was left running
   * for too long without a restart.
   */
  private var currentRecordingSessions = 0

  /**
   * Handler for what happens when the recording activity finishes.
   */
  private var handleRecordingResult = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result: ActivityResult ->
    run {
      currentRecordingSessions += 1
      when (result.resultCode) {
        RESULT_OK -> {
        }

        else -> {
          val text = getString(R.string.recording_session_ended_unexpectedly)
          val toast = Toast.makeText(this, text, Toast.LENGTH_LONG)
          toast.show()
        }
      }
    }
  }


  private val requestMultiplePermissions =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
      permissions.entries.forEach {
        val permissionName = it.key
        val isGranted = it.value
        val text = if (isGranted) {
          getString(R.string.permission_granted, permissionName)
        } else {
          getString(R.string.permission_denied, permissionName)
        }
        val toast = Toast.makeText(this, text, Toast.LENGTH_SHORT)
        toast.show()
      }
    }

  fun switchPromptsButtonAction() {
    startActivity(Intent(this, PromptSelectActivity::class.java))
  }


  /**
   * onCreate() function from Activity - called when the home screen activity is launched.
   */
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val isTablet = thisDeviceIsATablet(applicationContext)
    if (isTablet) {
      requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    } else {
      requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    }

    windowInsetsController =
      WindowCompat.getInsetsController(window, window.decorView).also {
        it.systemBarsBehavior =
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }

    dataManager = DataManager.getInstance(applicationContext)
    lifecycleScope.launch {
      dataManager.logToServerAndPersist("HomeScreenActivity.onCreate")
    }

    // Create the upload directory if it doesn't exist.
    val uploadDir = File(filesDir, "upload")
    if (!uploadDir.exists()) {
      if (uploadDir.mkdirs()) {
        Log.i(TAG, "Upload directory created.")
      } else {
        Log.e(TAG, "Failed to create upload directory.")
      }
    }

    // Start the UploadService (which should already be running anyway).
    runBlocking {
      delay(1000)
    }

    setContent {
      HomeScreenContent(
        onStartClick = ::startButtonAction,
        onUploadClick = ::uploadButtonAction,
        onSwitchPromptsClick = ::switchPromptsButtonAction
      )
    }

    // `resources.getIdentifier` is used intentionally to check for the existence of optional
    // credentials defined in a `credentials.xml` file, which may not be present at compile time.
    @SuppressLint("DiscouragedApi")
    fun hasResource(label: String, type: String = "string"): Boolean {
      return resources.getIdentifier(label, type, packageName) != 0
    }

    // Check if it's possible to send emails by confirming that all necessary information
    // is present (sender email, sender's password, recipient list)
    emailing = hasResource("confirmation_email_sender")
    if (!emailing) {
      Log.w(
        TAG, "Warning: the string resource `confirmation_email_sender` is" +
            " not defined."
      )
    }

    emailing = emailing && hasResource("confirmation_email_password")
    if (!emailing) {
      Log.w(
        TAG, "Warning: the string resource `confirmation_email_password`" +
            " is not defined."
      )
    }

    emailing = emailing && hasResource("confirmation_email_recipients", "array")
    if (!emailing) {
      Log.w(
        TAG, "Warning: the string resource" +
            " `confirmation_email_recipients` is not defined."
      )
    }

    if (!emailing) {
      Log.w(
        TAG, "Sending confirmation emails is disabled due to the above " +
            "constants not being defined. To resolve this issue, please see the " +
            "instructions in the README."
      )
    }

    checkAndRequestPermissions()
    dataManager.checkServerConnection()
  }

  private fun startButtonAction() {
    fun checkPermission(perm: String): Boolean {
      return ContextCompat.checkSelfPermission(applicationContext, perm) ==
          PackageManager.PERMISSION_GRANTED
    }

    if (checkPermission(CAMERA)) {
      lifecycleScope.launch {
        // You can use the API that requires the permission.
        val intent = Intent(
          this@HomeScreenActivity, RecordingActivity::class.java
        ).also {
          it.putExtra("SEND_CONFIRMATION_EMAIL", emailing)
        }
        UploadPauseManager.pauseUploadTimeout(UPLOAD_RESUME_ON_IDLE_TIMEOUT)
        Log.d(TAG, "Pausing uploads and waiting for data lock to be available.")
        // This has the side effect of ensuring the lock is available
        // (hence any upload is paused).
        dataManager.logToServerAndPersist("Launching RecordingActivity.")
        Log.d(TAG, "Data lock was available.")

        handleRecordingResult.launch(intent)
      }
    } else {
      val text = getString(R.string.enable_camera_access)
      val toast = Toast.makeText(applicationContext, text, Toast.LENGTH_LONG)
      toast.show()
    }
  }

  private fun uploadButtonAction() {
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        Log.d(TAG, "Delaying next upload work.")
        UploadWorkManager.scheduleNextPeriodic(applicationContext)
        UploadPauseManager.pauseUploadUntil(null)
        dataManager.dataManagerData._uploadState.postValue(
          UploadState(
            status = UploadStatus.UPLOADING,
            progress = 0
          )
        )
        dataManager.uploadData()
        Log.d(TAG, "Delaying next upload work.")
        UploadPauseManager.pauseUploadTimeoutAtLeast(UPLOAD_RESUME_ON_IDLE_TIMEOUT)
        UploadWorkManager.scheduleNextPeriodic(applicationContext)
      } catch (e: InterruptedUploadException) {
        dataManager.logToServerAndPersistNonBlocking(
          "Data upload was interrupted in HomeScreenActivity."
        )
      }
    }
  }

  private fun checkAndRequestPermissions() {
    val permissionsToRequest = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
      ) {
        permissionsToRequest.add(POST_NOTIFICATIONS)
      }
    }
    if (ContextCompat.checkSelfPermission(this, CAMERA) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      permissionsToRequest.add(CAMERA)
    }

    if (permissionsToRequest.isNotEmpty()) {
      requestMultiplePermissions.launch(permissionsToRequest.toTypedArray())
    }
  }

  override fun onResume() {
    super.onResume()
    UploadPauseManager.pauseUploadTimeoutAtLeast(UPLOAD_RESUME_ON_IDLE_TIMEOUT)
    windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
  }

  override fun onStop() {
    super.onStop()
    windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
  }

  override fun onDestroy() {
    super.onDestroy()
    if (isFinishing && isTaskRoot) {
      UploadPauseManager.pauseUploadTimeout(UPLOAD_RESUME_ON_ACTIVITY_FINISHED)
      runBlocking {
        dataManager.logToServerAndPersist("App is being closed.")
      }
    }
  }
}

@Composable
fun HomeScreenContent(
  onStartClick: () -> Unit,
  onUploadClick: () -> Unit,
  onSwitchPromptsClick: () -> Unit
) {
  val dataManager = DataManager.getInstance(LocalContext.current.applicationContext)
  val promptState by dataManager.promptState.observeAsState()
  val serverStatus by dataManager.serverStatus.observeAsState()
  val uploadState by dataManager.uploadState.observeAsState()
  var numTitleClicks by remember { mutableStateOf(0) }
  var showUploadStatus by remember { mutableStateOf(false) }
  var uploadStatusMessage by remember { mutableStateOf(null as String?) }

  val context = LocalContext.current
  val isTablet = thisDeviceIsATablet(context)
  LaunchedEffect(uploadState) {
    uploadState?.let {
      when (it.status) {
        UploadStatus.SUCCESS -> {
          uploadStatusMessage = context.getString(R.string.upload_complete)
          showUploadStatus = true
          delay(10000) // Keep visible for 10 seconds
          showUploadStatus = false
        }

        UploadStatus.FAILED -> {
          uploadStatusMessage = context.getString(R.string.upload_failed)
          showUploadStatus = true
          delay(10000) // Keep visible for 10 seconds
          showUploadStatus = false
        }

        UploadStatus.UPLOADING -> {
          uploadStatusMessage = null
          showUploadStatus = true
        }

        UploadStatus.INTERRUPTED -> {
          uploadStatusMessage = context.getString(R.string.upload_interrupted)
          showUploadStatus = true
          delay(10000) // Keep visible for 10 seconds
          showUploadStatus = false
        }

        UploadStatus.IDLE -> {
          uploadStatusMessage = null
          showUploadStatus = false
        }
      }
    }
  }

  // Total Progress Calculation
  var totalCompleted = 0
  var totalPrompts = 0
  promptState?.let { state ->
    val sections =
      state.promptsCollection?.sections?.values?.toList()?.sortedBy { it.name }
        ?: emptyList()
    sections.forEachIndexed { index, section ->
      val prompts = section.mainPrompts
      val total = prompts.array.size
      val sectionProgress = state.promptProgress[section.name]
      val completed = sectionProgress?.get("mainIndex") ?: 0
      totalCompleted += completed
      totalPrompts += total
    }
  }

  @Composable
  fun lifetimeMSTimeFormatter(milliseconds: Long): String {
    val context = LocalContext.current
    return milliseconds.milliseconds.toComponents { hours, minutes, seconds, nanoseconds ->
      if (hours == 0L) {
        context.getString(R.string.time_format_min_sec, minutes, seconds)
      } else {
        context.getString(R.string.time_format_hour_min_sec, hours, minutes, seconds)
      }
    }
  }

  val lifetimeRecordingCount by LocalContext.current.applicationContext.prefStore.data
    .map { it[intPreferencesKey("lifetimeRecordingCount")] ?: 0 }
    .collectAsState(initial = 0)
  val lifetimeRecordingMs by LocalContext.current.applicationContext.prefStore.data
    .map { it[longPreferencesKey("lifetimeRecordingMs")] ?: 0L }
    .collectAsState(initial = 0L)

  ConstraintLayout(
    modifier = Modifier
      .fillMaxSize()
      .background(colorResource(id = R.color.white))
  ) {
    val (backButton, header, versionText, loadingText, statisticsHeader, uploadProgressBarLayout, uploadButton, startButton, switchPromptsButton, uploadStatusMessageText, sectionsCompletedText, sectionsCompletedLayout) = createRefs()
    val centerGuideline = createGuidelineFromStart(0.5f)

    // 1. Back Button (ImageButton)
    val activity = LocalActivity.current
    Image(
      painter = painterResource(id = R.drawable.back_arrow),
      contentDescription = stringResource(id = R.string.back_button),
      modifier = Modifier
        .constrainAs(backButton) {
          start.linkTo(parent.start, margin = 16.dp)
          top.linkTo(parent.top, margin = 16.dp)
        }
        .clickable { activity?.finish() }
    )

    // Version Text (TextView)
    Text(
      text = stringResource(id = R.string.version_text, Constants.APP_VERSION),
      fontSize = 20.sp,
      color = colorResource(id = R.color.dark_gray),
      modifier = Modifier.constrainAs(versionText) {
        top.linkTo(backButton.top)
        end.linkTo(parent.end, margin = 24.dp)
      }
    )

    // 2. Header (TextView)
    val context = LocalContext.current
    Text(
      text = stringResource(id = R.string.app_name),
      fontSize = if (isTablet) 50.sp else 40.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier
        .constrainAs(header) {
          start.linkTo(parent.start, margin = 32.dp)
          end.linkTo(parent.end, margin = 32.dp)
          top.linkTo(backButton.bottom, margin = 6.dp)
        }
        .clickable {
          numTitleClicks++
          if (numTitleClicks == 5) {
            numTitleClicks = 0
            val intent = Intent(context, LoadDataActivity::class.java)
            context.startActivity(intent)
          }
        }
    )

    // 4. Loading Text (TextView)
    if (promptState?.promptsCollection == null) {
      Text(
        text = stringResource(id = R.string.loading),
        fontSize = 30.sp,
        modifier = Modifier
          .constrainAs(loadingText) {
            start.linkTo(parent.start)
            end.linkTo(parent.end)
            top.linkTo(header.bottom, margin = 32.dp)
          }
      )
      return@ConstraintLayout
    }

    val (deviceIdLabelText, deviceIdText, usernameLabelText, usernameText, internetStatusText) = createRefs()
    // 6. Session Information (LinearLayout)
    Text(
      text = stringResource(id = R.string.id_label),
      fontWeight = FontWeight.Bold,
      fontSize = 18.sp,
      modifier = Modifier
        .constrainAs(deviceIdLabelText) {
          top.linkTo(header.bottom, margin = 15.dp)
          end.linkTo(centerGuideline, margin = 4.dp)
        }
    )
    Text(
      text = promptState?.deviceId ?: stringResource(id = R.string.id_error),
      fontStyle = FontStyle.Italic,
      fontSize = 18.sp,
      modifier = Modifier
        .constrainAs(deviceIdText) {
          top.linkTo(header.bottom, margin = 15.dp)
          start.linkTo(centerGuideline)
        }
    )

    Text(
      text = stringResource(id = R.string.username_label),
      fontWeight = FontWeight.Bold,
      fontSize = 18.sp,
      modifier = Modifier
        .constrainAs(usernameLabelText) {
          top.linkTo(deviceIdLabelText.bottom, margin = 15.dp)
          end.linkTo(centerGuideline, margin = 4.dp)
        }
    )
    Text(
      text = promptState?.username ?: stringResource(id = R.string.username_error),
      fontStyle = FontStyle.Italic,
      fontSize = 18.sp,
      modifier = Modifier
        .constrainAs(usernameText) {
          top.linkTo(deviceIdLabelText.bottom, margin = 15.dp)
          start.linkTo(centerGuideline)
        }
    )

    // 8. Status Information (LinearLayout)
    val statusText: String
    val statusColor: Int

    when (serverStatus?.status ?: ServerStatus.UNKNOWN) {
      ServerStatus.UNKNOWN -> {
        statusText = stringResource(id = R.string.server_status)
        statusColor = R.color.alert_yellow
      }

      ServerStatus.NO_INTERNET -> {
        statusText = stringResource(id = R.string.internet_unavailable)
        statusColor = R.color.alert_red
      }

      ServerStatus.NO_SERVER -> {
        statusText = stringResource(id = R.string.server_unavailable)
        statusColor = R.color.alert_red
      }

      ServerStatus.SERVER_ERROR -> {
        statusText = stringResource(id = R.string.server_error)
        statusColor = R.color.alert_red
      }

      ServerStatus.NO_LOGIN -> {
        statusText = stringResource(id = R.string.server_unauthorized)
        statusColor = R.color.alert_red
      }

      ServerStatus.ACTIVE -> {
        statusText = stringResource(id = R.string.server_success)
        statusColor = R.color.alert_green
      }
    }

    Text(
      text = statusText,
      color = colorResource(id = statusColor),
      fontStyle = FontStyle.Italic,
      fontSize = 20.sp,
      modifier = Modifier
        .constrainAs(internetStatusText) {
          top.linkTo(usernameLabelText.bottom, margin = 15.dp)
          start.linkTo(parent.start)
          end.linkTo(parent.end)
        }
    )

    // 9. Statistics Header (TextView)
    Text(
      text = stringResource(id = R.string.statistics_header),
      fontSize = if (isTablet) 32.sp else 26.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.constrainAs(statisticsHeader) {
        start.linkTo(parent.start)
        end.linkTo(parent.end)
        top.linkTo(internetStatusText.bottom, margin = 30.dp)
      }
    )

    // 10. Statistics Information (ConstraintLayout)
    val (sectionProgressLabelText, sectionProgressText, totalProgressText, totalProgressCountText, recordingsProgressText, recordingCountText, recordingTimeText, recordingTimeParsedText) = createRefs()

    // Prompts Progress Text
    Text(
      text = stringResource(id = R.string.prompts_completed),
      fontWeight = FontWeight.Bold,
      fontSize = 18.sp,
      modifier = Modifier
        .constrainAs(sectionProgressLabelText) {
          top.linkTo(sectionProgressText.top)
          bottom.linkTo(sectionProgressText.bottom)
          end.linkTo(centerGuideline, margin = 4.dp)
        }
    )

    FlowRow(
      modifier = Modifier
        .constrainAs(sectionProgressText) {
          top.linkTo(statisticsHeader.bottom, margin = 15.dp)
          start.linkTo(centerGuideline)
          end.linkTo(parent.end, margin = 4.dp)
          width = Dimension.fillToConstraints
        },
      horizontalArrangement = Arrangement.spacedBy(6.dp, alignment = Alignment.Start),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      // Section Name Text
      Text(
        text = promptState?.currentSectionName ?: "",
        fontSize = 18.sp,
      )

      if (promptState?.tutorialMode == true) {
        Text(
          text = stringResource(id = R.string.tutorial_mode),
          color = colorResource(id = R.color.blue),
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold,
        )
      } else {
        Text(
          // TODO replace with stringResource.
          text = "${promptState?.currentPromptIndex ?: 0}/${promptState?.totalPromptsInCurrentSection ?: 0}",
          fontSize = 18.sp,
        )
      }
    }

    // Total Progress Text
    Text(
      text = stringResource(id = R.string.total_progress),
      fontWeight = FontWeight.Bold,
      fontSize = 18.sp,
      modifier = Modifier
        .constrainAs(totalProgressText) {
          top.linkTo(sectionProgressText.bottom, margin = 15.dp)
          end.linkTo(centerGuideline, margin = 4.dp)
        }
    )
    // Total Progress Count Text
    Text(
      // TODO replace with stringResource.
      text = "${totalCompleted}/${totalPrompts}",
      fontSize = 18.sp,
      modifier = Modifier
        .constrainAs(totalProgressCountText) {
          top.linkTo(sectionProgressText.bottom, margin = 15.dp)
          start.linkTo(centerGuideline)
        }
    )

    // Recordings Progress Text
    Text(
      text = stringResource(id = R.string.total_recordings),
      fontWeight = FontWeight.Bold,
      fontSize = 18.sp,
      modifier = Modifier
        .constrainAs(recordingsProgressText) {
          top.linkTo(totalProgressText.bottom, margin = 15.dp)
          end.linkTo(centerGuideline, margin = 4.dp)
        }
    )

    // Recording Count Text
    Text(
      text = lifetimeRecordingCount.toString(),
      fontSize = 18.sp,
      modifier = Modifier
        .constrainAs(recordingCountText) {
          top.linkTo(totalProgressText.bottom, margin = 15.dp)
          start.linkTo(centerGuideline)
        }
    )

    // Recording Time Text
    Text(
      text = stringResource(id = R.string.total_time),
      fontWeight = FontWeight.Bold,
      fontSize = 18.sp,
      modifier = Modifier
        .constrainAs(recordingTimeText) {
          top.linkTo(recordingsProgressText.bottom, margin = 15.dp)
          end.linkTo(centerGuideline, margin = 4.dp)
        }
    )

    // Recording Time Parsed Text
    Text(
      text = lifetimeMSTimeFormatter(lifetimeRecordingMs),
      fontSize = 18.sp,
      modifier = Modifier
        .constrainAs(recordingTimeParsedText) {
          top.linkTo(recordingsProgressText.bottom, margin = 15.dp)
          start.linkTo(centerGuideline)
        }
    )

    // Sections Completed Text
    Text(
      text = stringResource(id = R.string.sections_completed),
      fontWeight = FontWeight.Bold,
      fontSize = if (isTablet) 32.sp else 26.sp,
      modifier = Modifier
        .constrainAs(sectionsCompletedText) {
          top.linkTo(recordingTimeText.bottom, margin = 30.dp)
          start.linkTo(parent.start)
          end.linkTo(parent.end)
        }
    )

    // Sections Completed Layout (FlexboxLayout)
    FlowRow(
      modifier = Modifier
        .constrainAs(sectionsCompletedLayout) {
          top.linkTo(sectionsCompletedText.bottom, margin = 15.dp)
          start.linkTo(parent.start, margin = 16.dp)
          end.linkTo(parent.end, margin = 16.dp)
          width = Dimension.fillToConstraints
        },
      horizontalArrangement = Arrangement.spacedBy(6.dp, alignment = Alignment.CenterHorizontally),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      promptState?.promptsCollection?.sections?.values?.toList()?.sortedBy { it.name }
        ?.forEachIndexed { index, section ->
          val prompts = section.mainPrompts
          val total = prompts.array.size
          val sectionProgress = promptState?.promptProgress?.get(section.name)
          val completed = sectionProgress?.get("mainIndex") ?: 0
          val color = if (completed >= total) R.color.alert_green else R.color.alert_red

          Row {
            Text(
              text = section.name,
              fontSize = 18.sp,
              color = colorResource(id = color),
            )
            if (index < (promptState?.promptsCollection?.sections?.values?.size ?: 0) - 1) {
              Text(
                text = ",",
                fontSize = 18.sp,
                color = colorResource(id = R.color.black),
              )
            }
          }
        }
    }

    // 11. Upload Progress Bar Layout (LinearLayout with ProgressBar and TextView)
    if (showUploadStatus) {
      if (uploadStatusMessage != null) {
        Text(
          text = uploadStatusMessage ?: "",
          fontSize = 18.sp,
          color = colorResource(id = R.color.blue),
          fontWeight = FontWeight.Bold,
          modifier = Modifier
            .padding(start = 20.dp, end = 8.dp)
            .constrainAs(uploadStatusMessageText) {
              if (isTablet) {
                start.linkTo(parent.start, margin = 20.dp)
                end.linkTo(parent.end, margin = 20.dp)
                bottom.linkTo(uploadProgressBarLayout.top, margin = 16.dp)
              } else {
                end.linkTo(parent.end, margin = 16.dp)
                top.linkTo(uploadProgressBarLayout.bottom, margin = 16.dp)
              }
            },
        )
      }
      Row(
        modifier = Modifier
          .constrainAs(uploadProgressBarLayout) {
            start.linkTo(parent.start, margin = 20.dp)
            end.linkTo(parent.end, margin = 20.dp)
            bottom.linkTo(uploadButton.top, margin = 30.dp)
          },
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = stringResource(id = R.string.upload_progress),
          fontSize = 18.sp,
          color = colorResource(id = R.color.blue),
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(start = 20.dp, end = 12.dp)
        )
        LinearProgressIndicator(
          progress = uploadState?.progress?.toFloat()?.div(100f) ?: 0f,
          modifier = Modifier
            .weight(1f)
            .height(20.dp)
            .padding(end = 20.dp),
          color = colorResource(id = R.color.blue),
          backgroundColor = colorResource(id = R.color.very_light_gray)
        )
      }
    }

    // 12. Upload Button (AppCompatButton)
    val uploadButtonText = when (uploadState?.status) {
      UploadStatus.UPLOADING -> stringResource(id = R.string.upload_in_progress)
      UploadStatus.FAILED -> stringResource(id = R.string.upload_failed_try_again)
      else -> stringResource(id = R.string.upload_button)
    }
    SecondaryButton(
      onClick = { onUploadClick() },
      modifier = Modifier
        .constrainAs(uploadButton) {
          start.linkTo(parent.start, margin = 16.dp)
          bottom.linkTo(startButton.top, margin = 24.dp)
        },
      enabled = uploadState?.status != UploadStatus.UPLOADING,
      text = uploadButtonText
    )

    // 13. Start Button (AppCompatButton)
    val startButtonEnabled: Boolean
    val startButtonText: String
    val startRecordingShouldSwitchPrompts: Boolean

    if (promptState != null && promptState!!.currentPrompts != null && promptState!!.username != null) {
      if ((promptState!!.currentPromptIndex ?: 0) < (promptState!!.totalPromptsInCurrentSection
          ?: 0)
      ) {
        startButtonEnabled = true
        startRecordingShouldSwitchPrompts = false
        startButtonText = stringResource(id = R.string.start_button)
      } else {
        if (totalCompleted >= totalPrompts) {
          startButtonEnabled = false
          startRecordingShouldSwitchPrompts = false
          startButtonText = stringResource(id = R.string.no_more_prompts)
        } else {
          startButtonEnabled = true
          startRecordingShouldSwitchPrompts = true
          startButtonText = stringResource(id = R.string.switch_prompts)
        }
      }
    } else {
      startButtonEnabled = false
      startRecordingShouldSwitchPrompts = false
      startButtonText = stringResource(id = R.string.start_disabled)
    }

    PrimaryButton(
      onClick = {
        if (startRecordingShouldSwitchPrompts) onSwitchPromptsClick() else onStartClick()
      },
      modifier = Modifier
        .constrainAs(startButton) {
          end.linkTo(parent.end, margin = 16.dp)
          bottom.linkTo(parent.bottom, margin = 24.dp)
        },
      enabled = startButtonEnabled,
      text = startButtonText
    )

    if (!startRecordingShouldSwitchPrompts) {
      // 14. Switch Prompts Button (AppCompatButton)
      SecondaryButton(
        onClick = { onSwitchPromptsClick() },
        modifier = Modifier
          .constrainAs(switchPromptsButton) {
            start.linkTo(parent.start, margin = 16.dp)
            bottom.linkTo(parent.bottom, margin = 24.dp)
          },
        text = stringResource(id = R.string.switch_prompts)
      )
    }

  }
}

