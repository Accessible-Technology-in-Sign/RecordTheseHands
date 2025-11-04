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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import edu.gatech.ccg.recordthesehands.Constants
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_RESUME_ON_ACTIVITY_FINISHED
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_RESUME_ON_IDLE_TIMEOUT
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.upload.DataManager
import edu.gatech.ccg.recordthesehands.upload.UploadPauseManager
import edu.gatech.ccg.recordthesehands.upload.UploadStatus
import kotlinx.coroutines.delay
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
   * Make the startRecording button call switch prompts and return.
   */
  private var startRecordingShouldSwitchPrompts = false

  private var previousUploadStatus: UploadStatus? = null

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


  fun lifetimeMSTimeFormatter(milliseconds: Long): String {
    return milliseconds.milliseconds.toComponents { hours, minutes, seconds, nanoseconds ->
      if (hours == 0L) {
        getString(R.string.time_format_min_sec, minutes, seconds)
      } else {
        getString(R.string.time_format_hour_min_sec, hours, minutes, seconds)
      }
    }
  }

  /**
   * onCreate() function from Activity - called when the home screen activity is launched.
   */
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

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
      HomeScreenContent()
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
fun HomeScreenContent() {
  val dataManager = DataManager.getInstance(LocalContext.current.applicationContext)
  val promptState by dataManager.promptState.observeAsState()
  val serverStatus by dataManager.serverStatus.observeAsState()
  val uploadState by dataManager.uploadState.observeAsState()

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
  val lifetimeRecordingCount = 0
  val lifetimeRecordingMs = 0

  ConstraintLayout(
    modifier = Modifier.fillMaxSize().background(colorResource(id = R.color.white))
  ) {
    val (backButton, header, versionText, loadingText, sessionInformation, statusHeader, statusInformation, statisticsHeader, statisticsInformation, uploadProgressBarLayout, uploadButton, startButton, switchPromptsButton, tutorialModeContainer) = createRefs()

    // 1. Back Button (ImageButton)
    Image(
      painter = painterResource(id = R.drawable.back_arrow),
      contentDescription = stringResource(id = R.string.back_button),
      modifier = Modifier
        .constrainAs(backButton) {
          start.linkTo(parent.start, margin = 16.dp)
          top.linkTo(parent.top, margin = 16.dp)
        }
        .clickable { /* Handle back button click */ }
    )

    // 2. Header (TextView)
    Text(
      text = stringResource(id = R.string.app_name),
      fontSize = 50.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.constrainAs(header) {
        start.linkTo(parent.start, margin = 32.dp)
        end.linkTo(parent.end)
        top.linkTo(parent.top, margin = 24.dp)
      }
    )

    // 3. Version Text (TextView)
    Text(
      text = "Version: ${Constants.APP_VERSION}",
      fontSize = 20.sp,
      color = colorResource(id = R.color.dark_gray),
      modifier = Modifier.constrainAs(versionText) {
        top.linkTo(header.top)
        end.linkTo(parent.end, margin = 24.dp)
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
    }

    // 6. Session Information (LinearLayout)
    Row(
      modifier = Modifier
        .constrainAs(sessionInformation) {
          start.linkTo(header.start)
          end.linkTo(header.end)
          top.linkTo(header.bottom)
        },
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = stringResource(id = R.string.id_label),
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        modifier = Modifier.padding(end = 6.dp)
      )
      Text(
        text = promptState?.deviceId ?: stringResource(id = R.string.id_error),
        fontStyle = FontStyle.Italic,
        fontSize = 18.sp,
        modifier = Modifier.padding(end = 20.dp)
      )
      Text(
        text = stringResource(id = R.string.username_label),
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        modifier = Modifier.padding(start = 20.dp, end = 6.dp)
      )
      Text(
        text = promptState?.username ?: stringResource(id = R.string.username_error),
        fontStyle = FontStyle.Italic,
        fontSize = 18.sp
      )
    }

    // 7. Status Header (TextView)
    Text(
      text = stringResource(id = R.string.status_header),
      fontWeight = FontWeight.Bold,
      fontSize = 32.sp,
      modifier = Modifier.constrainAs(statusHeader) {
        start.linkTo(parent.start)
        end.linkTo(parent.end)
        top.linkTo(sessionInformation.bottom, margin = 30.dp)
      }
    )

    // 8. Status Information (LinearLayout)
    Row(
      modifier = Modifier
        .constrainAs(statusInformation) {
          start.linkTo(header.start)
          end.linkTo(header.end)
          top.linkTo(statusHeader.bottom)
        },
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = stringResource(id = R.string.internet_status),
        color = colorResource(R.color.alert_green), // if (serverStatus?.internetConnected == true) colorResource(id = R.color.green) else colorResource(id = R.color.alert_yellow),
        fontStyle = FontStyle.Italic,
        fontSize = 20.sp,
        modifier = Modifier.padding(end = 50.dp)
      )
      Text(
        text = stringResource(id = R.string.server_status),
        color = colorResource(R.color.alert_green), // if (serverStatus?.serverConnected == true) colorResource(id = R.color.green) else colorResource(id = R.color.alert_yellow),
        fontStyle = FontStyle.Italic,
        fontSize = 20.sp,
        modifier = Modifier.padding(start = 50.dp)
      )
    }

    // 9. Statistics Header (TextView)
    Text(
      text = stringResource(id = R.string.statistics_header),
      fontSize = 32.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.constrainAs(statisticsHeader) {
        start.linkTo(parent.start)
        end.linkTo(parent.end)
        top.linkTo(statusInformation.bottom, margin = 30.dp)
      }
    )

    // 10. Statistics Information (ConstraintLayout)
    ConstraintLayout(
      modifier = Modifier
        .constrainAs(statisticsInformation) {
          start.linkTo(parent.start)
          end.linkTo(parent.end)
          top.linkTo(statisticsHeader.bottom)
        }
        .padding(horizontal = 16.dp)
    ) {
      val (promptsProgressText, sectionNameText, completedAndTotalPromptsText, tutorialProgressText, totalProgressText, totalProgressCountText, recordingsProgressText, recordingCountText, recordingTimeText, recordingTimeParsedText, sessionCounterText, sessionCounterBox, sectionsCompletedText, sectionsCompletedLayout) = createRefs()
      val guideline = createGuidelineFromStart(0.5f)

      // Prompts Progress Text
      Text(
        text = stringResource(id = R.string.prompts_completed),
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        modifier = Modifier
          .constrainAs(promptsProgressText) {
            top.linkTo(parent.top, margin = 15.dp)
            end.linkTo(guideline)
          }
      )

      // Section Name Text
      Text(
        text = promptState?.currentSectionName ?: "",
        fontSize = 18.sp,
        modifier = Modifier
          .constrainAs(sectionNameText) {
            top.linkTo(parent.top, margin = 15.dp)
            start.linkTo(guideline)
          }
          .padding(end = 8.dp)
      )

      // Completed and Total Prompts Text
      Text(
        text = "${promptState?.currentPromptIndex ?: 0}/${promptState?.totalPromptsInCurrentSection ?: 0}",
        fontSize = 18.sp,
        modifier = Modifier
          .constrainAs(completedAndTotalPromptsText) {
            top.linkTo(parent.top, margin = 15.dp)
            start.linkTo(sectionNameText.end)
          }
      )

      // Tutorial Progress Text
      Text(
        text = stringResource(id = R.string.tutorial_mode),
        color = colorResource(id = R.color.blue),
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
          .constrainAs(tutorialProgressText) {
            top.linkTo(parent.top, margin = 15.dp)
            start.linkTo(sectionNameText.end)
          }
          .alpha(if (promptState?.tutorialMode == true) 1f else 0f) // Control visibility
      )

      // Total Progress Text
      Text(
        text = stringResource(id = R.string.total_progress),
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        modifier = Modifier
          .constrainAs(totalProgressText) {
            top.linkTo(promptsProgressText.bottom, margin = 15.dp)
            end.linkTo(guideline)
          }
      )
      // Total Progress Count Text
      Text(
        text = "${totalCompleted ?: 0}/${totalPrompts ?: 0}",
        fontSize = 18.sp,
        modifier = Modifier
          .constrainAs(totalProgressCountText) {
            top.linkTo(promptsProgressText.bottom, margin = 15.dp)
            start.linkTo(guideline)
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
            end.linkTo(guideline)
          }
      )

      // Recording Count Text
      Text(
        text = lifetimeRecordingCount.toString() ?: stringResource(id = R.string.counter),
        fontSize = 18.sp,
        modifier = Modifier
          .constrainAs(recordingCountText) {
            top.linkTo(totalProgressText.bottom, margin = 15.dp)
            start.linkTo(guideline)
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
            end.linkTo(guideline)
          }
      )

      // Recording Time Parsed Text
      Text(
        text = lifetimeRecordingMs.toString() ?: stringResource(id = R.string.counter),
        fontSize = 18.sp,
        modifier = Modifier
          .constrainAs(recordingTimeParsedText) {
            top.linkTo(recordingsProgressText.bottom, margin = 15.dp)
            start.linkTo(guideline)
          }
      )

      // Session Counter Text
      Text(
        text = stringResource(id = R.string.total_sessions),
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        modifier = Modifier
          .constrainAs(sessionCounterText) {
            top.linkTo(recordingTimeText.bottom, margin = 15.dp)
            end.linkTo(guideline)
          }
      )

      // Session Counter Box
      Text(
        text = "TODO total sessions" ?: stringResource(id = R.string.counter),
        fontSize = 18.sp,
        modifier = Modifier
          .constrainAs(sessionCounterBox) {
            top.linkTo(recordingTimeText.bottom, margin = 15.dp)
            start.linkTo(guideline)
          }
      )

      // Sections Completed Text
      Text(
        text = stringResource(id = R.string.sections_completed),
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        modifier = Modifier
          .constrainAs(sectionsCompletedText) {
            top.linkTo(sessionCounterText.bottom, margin = 15.dp)
            end.linkTo(guideline)
          }
      )

      // Sections Completed Layout (FlexboxLayout)
      Row(
        modifier = Modifier
          .constrainAs(sectionsCompletedLayout) {
            top.linkTo(sessionCounterText.bottom, margin = 15.dp)
            start.linkTo(guideline)
            end.linkTo(parent.end)
          }
      ) {
        promptState?.promptsCollection?.sections?.forEach { section ->
          Text(text = section.key, modifier = Modifier.padding(end = 4.dp))
          // TODO Green if the section isn't done.
        }
      }
    }

    // 11. Upload Progress Bar Layout (LinearLayout with ProgressBar and TextView)
    Row(
      modifier = Modifier
        .constrainAs(uploadProgressBarLayout) {
          start.linkTo(parent.start, margin = 20.dp)
          end.linkTo(parent.end, margin = 20.dp)
          bottom.linkTo(tutorialModeContainer.top)
        },
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (uploadState?.status == UploadStatus.UPLOADING) {
        Text(
          text = "Upload Progress: ",
          fontSize = 18.sp,
          color = colorResource(id = R.color.blue),
          fontWeight = FontWeight.Bold,
          modifier = Modifier
            .padding(bottom = 32.dp)
        )
        LinearProgressIndicator(
          progress = uploadState?.progress?.toFloat()?.div(100f) ?: 0f,
          modifier = Modifier
            .weight(1f)
            .height(32.dp)
        )
      }
    }

    // 12. Upload Button (AppCompatButton)
    Button(
      onClick = { /* Handle upload button click */ },
      modifier = Modifier
        .constrainAs(uploadButton) {
          start.linkTo(parent.start)
          end.linkTo(startButton.start)
          bottom.linkTo(parent.bottom, margin = 24.dp)
        },
    ) {
      Text(text = stringResource(id = R.string.upload_button))
    }

    // 13. Start Button (AppCompatButton)
    Button(
      onClick = { /* Handle start button click */ },
      modifier = Modifier
        .constrainAs(startButton) {
          start.linkTo(parent.start)
          end.linkTo(parent.end)
          bottom.linkTo(parent.bottom, margin = 24.dp)
        },
    ) {
      Text(text = stringResource(id = R.string.start_button))
    }

    // 14. Switch Prompts Button (AppCompatButton)
    Button(
      onClick = { /* Handle switch prompts button click */ },
      modifier = Modifier
        .constrainAs(switchPromptsButton) {
          start.linkTo(startButton.end)
          end.linkTo(parent.end)
          bottom.linkTo(parent.bottom, margin = 24.dp)
        },
    ) {
      Text(text = stringResource(id = R.string.switch_prompts))
    }

    // 15. Tutorial Mode Container (LinearLayout with TextView)
    Row(
      modifier = Modifier
        .constrainAs(tutorialModeContainer) {
          start.linkTo(parent.start)
          end.linkTo(parent.end)
          bottom.linkTo(uploadButton.top, margin = 24.dp)
        },
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = stringResource(id = R.string.tutorial_mode),
        fontSize = 32.sp,
        color = colorResource(id = R.color.blue),
        fontWeight = FontWeight.Bold,
        modifier = Modifier.alpha(if (promptState?.tutorialMode == true) 1f else 0f) // Control visibility
      )
    }
  }
}

