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
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.Group
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.lifecycle.lifecycleScope
import edu.gatech.ccg.recordthesehands.Constants
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_RESUME_ON_IDLE_TIMEOUT
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.databinding.ActivitySplashBinding
import edu.gatech.ccg.recordthesehands.hapticFeedbackOnTouchListener
import edu.gatech.ccg.recordthesehands.recording.RecordingActivity
import edu.gatech.ccg.recordthesehands.upload.DataManager
import edu.gatech.ccg.recordthesehands.upload.InterruptedUploadException
import edu.gatech.ccg.recordthesehands.upload.UploadService
import edu.gatech.ccg.recordthesehands.upload.prefStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
          val text = "The recording session was ended due to an unexpected error."
          val toast = Toast.makeText(this, text, Toast.LENGTH_LONG)
          toast.show()
        }
      }
    }
  }

  /**
   * Check permissions necessary to begin recording session
   */
  private val requestRecordingPermissions =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
      val accessCamera = map[CAMERA] ?: false

      if (accessCamera) {
        // Permission is granted.
        lifecycleScope.launch {
          val intent = Intent(
            this@HomeScreenActivity, RecordingActivity::class.java
          ).also {
            it.putExtra("SEND_CONFIRMATION_EMAIL", emailing)
          }
          UploadService.pauseUploadTimeout(UPLOAD_RESUME_ON_IDLE_TIMEOUT)
          // TODO this does not work the second time through.  Or at least not when the network
          // isn't working.  Probably some thread has acquired the lock twice and is deadlocked.
          Log.d(TAG, "Pausing uploads and waiting for data lock to be available.")
          dataManager.waitForDataLock()
          Log.d(TAG, "Data lock was available.")

          handleRecordingResult.launch(intent)
        }
      } else {
        // Permission is not granted.
        val text = "Cannot begin recording since permissions not granted"
        val toast = Toast.makeText(this, text, Toast.LENGTH_SHORT)
        toast.show()
      }
    }

  /**
   * Sets up all of the UI elements.
   */
  // The `setOnTouchListener` is used for haptic feedback and the click is handled by a separate
  // `setOnClickListener`, so `performClick` does not need to be called manually.
  @SuppressLint("ClickableViewAccessibility")
  private fun setupUI() {
    lifecycleScope.launch {

      val loadingText = findViewById<TextView>(R.id.loadingText)
      loadingText.visibility = View.GONE
      val mainGroup = findViewById<Group>(R.id.mainGroup)
      mainGroup.visibility = View.VISIBLE

      // Listener for the super secret admin menu accessed by touching the
      // titleText (i.e. "RecordTheseHands") 5 times in a row.
      val titleText = findViewById<TextView>(R.id.header)
      var numTitleClicks = 0
      titleText.setOnClickListener {
        numTitleClicks += 1
        if (numTitleClicks == 5) {
          numTitleClicks = 0
          val intent = Intent(this@HomeScreenActivity, LoadDataActivity::class.java)
          startActivity(intent)
        }
      }
      titleText.isSoundEffectsEnabled = false

      // exitTutorialMode button listener.
      val exitTutorialModeButton = findViewById<Button>(R.id.exitTutorialModeButton)
      exitTutorialModeButton.setOnTouchListener(::hapticFeedbackOnTouchListener)
      exitTutorialModeButton.setOnClickListener {
        CoroutineScope(Dispatchers.IO).launch {
          dataManager.setTutorialMode(false)
        }
      }

      // Setup the statistics.
      val recordingCountKeyObject = intPreferencesKey("lifetimeRecordingCount")
      val lifetimeRecordingCount = applicationContext.prefStore.data
        .map {
          it[recordingCountKeyObject]
        }.firstOrNull() ?: 0
      val recordingCountText = findViewById<TextView>(R.id.recordingCountText)
      recordingCountText.text = lifetimeRecordingCount.toString()
      val recordingMsKeyObject = longPreferencesKey("lifetimeRecordingMs")
      val lifetimeRecordingMs = applicationContext.prefStore.data
        .map {
          it[recordingMsKeyObject]
        }.firstOrNull() ?: 0L
      val recordingTimeBox = findViewById<TextView>(R.id.recordingTimeParsedText)
      recordingTimeBox.text = lifetimeMSTimeFormatter(lifetimeRecordingMs)
      val sessionCounterBox = findViewById<TextView>(R.id.sessionCounterBox)
      sessionCounterBox.text = currentRecordingSessions.toString()

      val startRecordingButton = findViewById<Button>(R.id.startButton)
      startRecordingButton.setOnTouchListener(::hapticFeedbackOnTouchListener)
      startRecordingButton.setOnClickListener {
        if(this@HomeScreenActivity.startRecordingShouldSwitchPrompts) {
          val switchPromptsButton = findViewById<Button>(R.id.switchPromptsButton)
          switchPromptsButton.performClick()
          return@setOnClickListener
        }
        fun checkPermission(perm: String): Boolean {
          return ContextCompat.checkSelfPermission(applicationContext, perm) ==
              PackageManager.PERMISSION_GRANTED
        }

        fun shouldAsk(perm: String): Boolean {
          return shouldShowRequestPermissionRationale(perm)
        }

        fun cannotGetPermission(perm: String): Boolean {
          return !checkPermission(perm) && !shouldAsk(perm)
        }

        Log.d(TAG, "Camera allowed: ${checkPermission(CAMERA)}")
        Log.d(TAG, "Ask for camera permission: ${shouldAsk(CAMERA)}")

        if (checkPermission(CAMERA)) {
          lifecycleScope.launch {
            // You can use the API that requires the permission.
            val intent = Intent(
              this@HomeScreenActivity, RecordingActivity::class.java
            ).also {
              it.putExtra("SEND_CONFIRMATION_EMAIL", emailing)
            }
            UploadService.pauseUploadTimeout(UPLOAD_RESUME_ON_IDLE_TIMEOUT)
            Log.d(TAG, "Pausing uploads and waiting for data lock to be available.")
            dataManager.waitForDataLock()
            Log.d(TAG, "Data lock was available.")

            handleRecordingResult.launch(intent)
          }
        } else if (!permissionRequestedPreviously) {
          // No permissions, and we haven't asked for permissions before
          permissionRequestedPreviously = true
          CoroutineScope(Dispatchers.IO).launch {
            val keyObject = booleanPreferencesKey("permissionRequestedPreviously")
            applicationContext.prefStore.edit {
              it[keyObject] = true
            }
          }
          requestRecordingPermissions.launch(arrayOf(CAMERA))
        } else if (shouldAsk(CAMERA)) {
          // We've asked the user for permissions before, and the prior `when` case failed,
          // so we are allowed to ask for at least one of the required permissions

          // Send an alert prompting the user that they need to grant permissions
          val builder = AlertDialog.Builder(applicationContext).apply {
            setTitle(getString(R.string.perm_alert))
            setMessage(getString(R.string.perm_alert_message))

            setPositiveButton(getString(R.string.ok)) { dialog, _ ->
              requestRecordingPermissions.launch(
                arrayOf(CAMERA)
              )
              dialog.dismiss()
            }
          }

          val dialog = builder.create()
          dialog.apply {
            setCanceledOnTouchOutside(true)
            setOnCancelListener {
              requestRecordingPermissions.launch(
                arrayOf(CAMERA)
              )
            }
            show()
          }
        } else if (cannotGetPermission(CAMERA)) {
          // We've asked the user for permissions before, they haven't been granted,
          // and we cannot ask the user for either camera or storage permissions (we already
          // asked them before)
          val text = "Please enable camera access in Settings"
          val toast = Toast.makeText(applicationContext, text, Toast.LENGTH_LONG)
          toast.show()
        } else {
          Log.e(TAG, "Invalid permission state.")
          val text =
            "The app is in a bad state, you likely need to enable camera access in Settings."
          val toast = Toast.makeText(applicationContext, text, Toast.LENGTH_LONG)
          toast.show()
        }
      } // setRecordingButton.onClickListener

      val uploadButton = findViewById<Button>(R.id.uploadButton)
      uploadButton.setOnTouchListener(::hapticFeedbackOnTouchListener)
      uploadButton.setOnClickListener {
        // Disable the button and start the upload process
        uploadButton.isEnabled = false
        uploadButton.isClickable = false
        uploadButton.text = getString(R.string.upload_successful)

        // Progress bar appears after confirming
        val uploadProgressBarText = findViewById<TextView>(R.id.uploadProgressBarText)
        uploadProgressBarText.visibility = View.VISIBLE
        val uploadProgressBar = findViewById<ProgressBar>(R.id.uploadProgressBar)
        uploadProgressBar.visibility = View.VISIBLE
        uploadProgressBar.progress = 0

        lifecycleScope.launch(Dispatchers.IO) {
          UploadService.pauseUploadUntil(null)
          try {
            val uploadSucceeded = dataManager.uploadData { progress ->
              runOnUiThread {
                Log.d(TAG, "Updating ProgressBar to $progress%")
                uploadProgressBar.progress = progress
              }
            }

            runOnUiThread {
              uploadButton.isEnabled = true
              uploadButton.isClickable = true
              uploadProgressBar.visibility = View.GONE
              uploadProgressBarText.visibility = View.GONE
              if (uploadSucceeded) {
                uploadButton.text = getString(R.string.upload_button)
              } else {
                uploadButton.text = getString(R.string.upload_failed)
                val textFinish = "Upload Failed"
                val toastFinish =
                  Toast.makeText(applicationContext, textFinish, Toast.LENGTH_LONG)
                toastFinish.show()
              }
            }
          } catch (e: InterruptedUploadException) {
            Log.w(TAG, "Upload Data was interrupted.", e)
            runOnUiThread {
              val textFinish = "Upload interrupted"
              val toastFinish =
                Toast.makeText(applicationContext, textFinish, Toast.LENGTH_LONG)
              toastFinish.show()
              uploadButton.isEnabled = true
              uploadButton.isClickable = true
              uploadProgressBar.visibility = View.GONE
              uploadProgressBarText.visibility = View.GONE
              uploadButton.text = getString(R.string.upload_button)
            }
          }
        }
      }
    }
  }

  fun lifetimeMSTimeFormatter(milliseconds: Long): String {
    val min = milliseconds / 60000
    val sec = (milliseconds % 60000) / 1000
    return getString(R.string.time_format_min_sec, min, sec)
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

    dataManager = DataManager(applicationContext)

    // Start the UploadService (which should already be running anyway).
    runBlocking {
      delay(1000)
    }
    Log.d(TAG, "Starting UploadService from HomeScreenActivity.onCreate")
    applicationContext.startForegroundService(Intent(applicationContext, UploadService::class.java))
    // Load UI from XML
    val binding = ActivitySplashBinding.inflate(layoutInflater)
    val view = binding.root
    setContentView(view)

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

    lifecycleScope.launch {
      val keyObject = booleanPreferencesKey("permissionRequestedPreviously")
      permissionRequestedPreviously = applicationContext.prefStore.data
        .map {
          it[keyObject]
        }.firstOrNull() ?: false
      setupUI()
    }

    val switchPromptsButton = findViewById<Button>(R.id.switchPromptsButton)
    switchPromptsButton.setOnClickListener {
      startActivity(Intent(this, PromptSelectActivity::class.java))
    }

    dataManager.promptState.observe(this@HomeScreenActivity) { state ->
      this@HomeScreenActivity.startRecordingShouldSwitchPrompts = false
      val startRecordingButton = findViewById<Button>(R.id.startButton)
      val tutorialModeText = findViewById<TextView>(R.id.tutorialModeText)
      val exitTutorialModeButton = findViewById<Button>(R.id.exitTutorialModeButton)
      tutorialModeText.visibility = if (state.tutorialMode) View.VISIBLE else View.GONE

      // Total Progress Calculation
      var totalCompleted = 0
      var totalPrompts = 0
      val sections =
        state.promptsCollection?.sections?.values?.toList()?.sortedBy { it.name } ?: return@observe
      sections.forEachIndexed { index, section ->
        val prompts = section.mainPrompts
        val total = prompts.array.size
        val sectionProgress = state.promptProgress[section.name]
        val completed = sectionProgress?.get("mainIndex") ?: 0
        totalCompleted += completed
        totalPrompts += total
      }

      if (state.tutorialMode && ((state.currentPromptIndex ?: 0) > 0 ||
            (state.totalPromptsInCurrentSection ?: 0) == 0)
      ) {
        exitTutorialModeButton.visibility = View.VISIBLE
        tutorialModeText.visibility = View.GONE
      } else {
        exitTutorialModeButton.visibility = View.GONE
      }

      startRecordingButton.visibility = View.VISIBLE
      if (state.currentPrompts != null && state.username != null) {
        if ((state.currentPromptIndex ?: 0) < (state.totalPromptsInCurrentSection ?: 0)) {
          startRecordingButton.isEnabled = true
          startRecordingButton.isClickable = true
          startRecordingButton.text = getString(R.string.start_button)
        } else {
          if (totalCompleted >= totalPrompts) {
            startRecordingButton.isEnabled = false
            startRecordingButton.isClickable = false
            startRecordingButton.text = getString(R.string.no_more_prompts)
          } else {
            startRecordingButton.isEnabled = true
            startRecordingButton.isClickable = true
            startRecordingButton.text = getString(R.string.switch_prompts)
            this@HomeScreenActivity.startRecordingShouldSwitchPrompts = true
          }
        }
      } else {
        startRecordingButton.isEnabled = false
        startRecordingButton.isClickable = false
        startRecordingButton.text = getString(R.string.start_disabled)
      }

      if (state.username != null) {
        val usernameBox = findViewById<TextView>(R.id.usernameBox)
        usernameBox.text = state.username
      }

      if (state.deviceId != null) {
        val deviceIdBox = findViewById<TextView>(R.id.deviceIdBox)
        deviceIdBox.text = state.deviceId
      }

      val tutorialProgressText = findViewById<TextView>(R.id.tutorialProgressText)
      val completedAndTotalPromptsText = findViewById<TextView>(R.id.completedAndTotalPromptsText)
      val sectionNameText = findViewById<TextView>(R.id.sectionNameText)

      sectionNameText.text = state.currentSectionName ?: "<Section Not Set>"

      if (state.tutorialMode) {
        tutorialProgressText.visibility = View.VISIBLE
        completedAndTotalPromptsText.visibility = View.GONE
      } else {
        tutorialProgressText.visibility = View.GONE
        completedAndTotalPromptsText.visibility = View.VISIBLE
        val completedPrompts = (state.currentPromptIndex ?: 0).toString()
        val totalPrompts = (state.totalPromptsInCurrentSection ?: 0).toString()
        completedAndTotalPromptsText.text =
          getString(R.string.ratio, completedPrompts, totalPrompts)
      }

      val sectionsCompletedLayout =
        findViewById<com.google.android.flexbox.FlexboxLayout>(R.id.sectionsCompletedLayout)
      sectionsCompletedLayout.removeAllViews()

      sections.forEachIndexed { index, section ->
        val prompts = section.mainPrompts
        val sectionProgress = state.promptProgress[section.name]
        val completed = sectionProgress?.get("mainIndex") ?: 0
        val total = prompts.array.size

        if (index > 0) {
          val space = TextView(this).apply { text = " " }
          sectionsCompletedLayout.addView(space)
        }

        val textView = TextView(this).apply {
          text = section.name
          val color = if (completed >= total) R.color.alert_green else R.color.alert_red
          setTextColor(ContextCompat.getColor(this@HomeScreenActivity, color))
        }
        sectionsCompletedLayout.addView(textView)
      }

      val totalProgressCountText = findViewById<TextView>(R.id.totalProgressCountText)
      totalProgressCountText.text =
        getString(R.string.ratio, totalCompleted.toString(), totalPrompts.toString())
    }

    dataManager.serverStatus.observe(this@HomeScreenActivity) { isConnected ->
      val internetConnectionText = findViewById<TextView>(R.id.internetConnectionText)
      val serverConnectionText = findViewById<TextView>(R.id.serverConnectionText)
      val connectivityManager =
        applicationContext.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
      val network = connectivityManager.activeNetwork

      internetConnectionText.visibility = View.VISIBLE
      serverConnectionText.visibility = View.VISIBLE

      if (network == null) {
        internetConnectionText.text = getString(R.string.internet_failed)
        internetConnectionText.setTextColor(
          ContextCompat.getColor(
            this@HomeScreenActivity,
            R.color.alert_red
          )
        )
        serverConnectionText.text = getString(R.string.server_failed)
        serverConnectionText.setTextColor(
          ContextCompat.getColor(
            this@HomeScreenActivity,
            R.color.alert_red
          )
        )
      } else {
        internetConnectionText.text = getString(R.string.internet_success)
        internetConnectionText.setTextColor(
          ContextCompat.getColor(
            this@HomeScreenActivity,
            R.color.alert_green
          )
        )
        if (isConnected) {
          serverConnectionText.text = getString(R.string.server_success)
          serverConnectionText.setTextColor(
            ContextCompat.getColor(
              this@HomeScreenActivity,
              R.color.alert_green
            )
          )
        } else {
          serverConnectionText.text = getString(R.string.server_failed)
          serverConnectionText.setTextColor(
            ContextCompat.getColor(
              this@HomeScreenActivity,
              R.color.alert_red
            )
          )
        }
      }
    }
    dataManager.checkServerConnection()
  }

  override fun onResume() {
    super.onResume()
    UploadService.pauseUploadTimeout(UPLOAD_RESUME_ON_IDLE_TIMEOUT)
    windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
  }

  override fun onStop() {
    super.onStop()
    windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
  }
}

