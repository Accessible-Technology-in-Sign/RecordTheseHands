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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.Group
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.lifecycle.lifecycleScope
import edu.gatech.ccg.recordthesehands.*
import edu.gatech.ccg.recordthesehands.Constants.APP_VERSION
import edu.gatech.ccg.recordthesehands.Constants.MAX_RECORDINGS_IN_SITTING
import edu.gatech.ccg.recordthesehands.databinding.ActivitySplashBinding
import edu.gatech.ccg.recordthesehands.recording.RecordingActivity
import edu.gatech.ccg.recordthesehands.upload.DataManager
import edu.gatech.ccg.recordthesehands.upload.InterruptedUploadException
import edu.gatech.ccg.recordthesehands.upload.Prompts
import edu.gatech.ccg.recordthesehands.upload.UploadService
import edu.gatech.ccg.recordthesehands.upload.prefStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * The home page for the app. The user can see statistics and start recording from this page.
 */
class HomeScreenActivity : ComponentActivity() {

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

  private var prompts: Prompts? = null
  private var username: String? = null
  private lateinit var deviceId: String

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
                this@HomeScreenActivity, RecordingActivity::class.java).also {
            it.putExtra("SEND_CONFIRMATION_EMAIL", emailing)
          }
          UploadService.pauseUploadTimeout(UploadService.UPLOAD_RESUME_ON_IDLE_TIMEOUT)
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

  private fun updateConnectionUi() {
    // TODO When reloading the app, this will generally run before dataManager.runDirectives
    // runs.  This means, that the data will be incorrect and won't be reloaded until
    // the upload now button is pressed or the app is restarted.  The best way to fix this
    // would be to have dataManager broadcast a message every time the server is pinged.
    val connectivityManager =
      applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork
    runOnUiThread {
      val internetConnectionText = findViewById<TextView>(R.id.internetConnectionText)
      if (network == null) {
        internetConnectionText.visibility = View.VISIBLE
        internetConnectionText.text = "Internet Unavailable"
      } else {
        if (dataManager.connectedToServer()) {
          internetConnectionText.visibility = View.INVISIBLE
        } else {
          internetConnectionText.visibility = View.VISIBLE
        }
        internetConnectionText.text = "Internet Connected"
      }

      val serverConnectionText = findViewById<TextView>(R.id.serverConnectionText)
      if (dataManager.connectedToServer()) {
        serverConnectionText.visibility = View.INVISIBLE
        serverConnectionText.text = "Connected to Server"
      } else {
        serverConnectionText.visibility = View.VISIBLE
        serverConnectionText.text = "Unable to connect to Server"
      }
    }
  }

  /**
   * Sets up the UI with a loading screen
   */
  private fun setupLoadingUI() {
    val startRecordingButton = findViewById<Button>(R.id.startButton)
    startRecordingButton.isEnabled = false
    startRecordingButton.isClickable = false
    startRecordingButton.text = "Cannot Start"
    val versionText = findViewById<TextView>(R.id.versionText)
    versionText.text = "v$APP_VERSION"
    val loadingText = findViewById<TextView>(R.id.loadingText)
    loadingText.visibility = View.VISIBLE
    val mainGroup = findViewById<Group>(R.id.mainGroup)
    mainGroup.visibility = View.GONE
  }

  /**
   * Sets up all of the UI elements.
   */
  private fun setupUI() {
    lifecycleScope.launch {
      val backArrow = findViewById<ImageButton>(R.id.backButton)
      backArrow.setOnClickListener {
        lifecycleScope.launch(Dispatchers.IO) {
          dataManager.setTutorialMode(false)
          dataManager.getPrompts()?.also {
            it.promptIndex = 0
            it.savePromptIndex()
          }

          withContext(Dispatchers.Main) {
            val intent = Intent(this@HomeScreenActivity, PromptSelectActivity::class.java)
            startActivity(intent)
            Log.i(TAG, "Going back to prompt selection")
            finish()
          }
        }
      }

      val loadingText = findViewById<TextView>(R.id.loadingText)
      loadingText.visibility = View.GONE
      val mainGroup = findViewById<Group>(R.id.mainGroup)
      mainGroup.visibility = View.VISIBLE

      val startRecordingButton = findViewById<Button>(R.id.startButton)
//      val exitTutorialModeButton = findViewById<Button>(R.id.exitTutorialModeButton)
      val tutorialModeText = findViewById<TextView>(R.id.tutorialModeText)
//      exitTutorialModeButton.visibility = View.GONE

      updateConnectionUi()

      val deviceIdBox= findViewById<TextView>(R.id.deviceIdBox)
      deviceIdBox.text = deviceId

      if (username != null) {
        val usernameBox = findViewById<TextView>(R.id.usernameBox)
        usernameBox.text = username
      }
      val tutorialMode = dataManager.getTutorialMode()
      if (tutorialMode) {
        tutorialModeText.visibility = View.VISIBLE
      } else {
        tutorialModeText.visibility = View.GONE
      }

      val numPrompts = prompts?.array?.size
      val promptIndex = prompts?.promptIndex

      if (prompts == null) {
        startRecordingButton.isEnabled = true
        startRecordingButton.isClickable = true
        startRecordingButton.text = "Cannot Start"
      } else if (username != null) {
        if (promptIndex!! < numPrompts!!) {
          startRecordingButton.isEnabled = true
          startRecordingButton.isClickable = true
          startRecordingButton.text = "Start"
        } else {
          startRecordingButton.visibility = View.GONE
        }
      }
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
      if (prompts != null) {
        val promptsProgressBox = findViewById<TextView>(R.id.completedAndTotalPromptsText)
        val completedPrompts = prompts!!.promptIndex.toString()
        val totalPrompts = prompts!!.array.size.toString()
        promptsProgressBox.text = "${completedPrompts} of ${totalPrompts}"

//        if (tutorialMode && (currentRecordingSessions > 0 ||
//              (promptIndex ?: 0) >= (numPrompts ?: 0))
//        )
//        {
//          exitTutorialModeButton.visibility = View.VISIBLE
//          exitTutorialModeButton.setOnTouchListener(::hapticFeedbackOnTouchListener)
//          exitTutorialModeButton.setOnClickListener {
//            CoroutineScope(Dispatchers.IO).launch {
//              dataManager.setTutorialMode(false)
//              dataManager.getPrompts()?.also {
//                it.promptIndex = 0
//                it.savePromptIndex()
//              }
//              finish()
//            }
//          }
//        }
      }

      startRecordingButton.setOnTouchListener(::hapticFeedbackOnTouchListener)
      startRecordingButton.setOnClickListener {
        if (prompts == null) {
          AlertDialog.Builder(this@HomeScreenActivity)
            .setTitle("No Prompts Available")
            .setMessage("No prompts have been downloaded. Please download prompts before starting.")
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
        } else {
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
              UploadService.pauseUploadTimeout(UploadService.UPLOAD_RESUME_ON_IDLE_TIMEOUT)
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
          } else if (permissionRequestedPreviously && shouldAsk(CAMERA)) {
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
          } else if (permissionRequestedPreviously && cannotGetPermission(CAMERA)) {
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
        }
      } // setRecordingButton.onClickListener

      val uploadButton = findViewById<Button>(R.id.uploadButton)
      uploadButton.setOnTouchListener(::hapticFeedbackOnTouchListener)
      uploadButton.setOnClickListener {
        // Show a confirmation dialog before proceeding with the upload
        val builder = AlertDialog.Builder(this@HomeScreenActivity).apply {
          setTitle(getString(R.string.upload_alert))
          setMessage(getString(R.string.upload_alert_message))

          setPositiveButton(getString(R.string.yes)) { dialog, _ ->
            // User confirmed, proceed with the upload
            Log.i(TAG, "User confirmed upload.")
            dialog.dismiss()

            // Disable the button and start the upload process
            uploadButton.isEnabled = false
            uploadButton.isClickable = false
            uploadButton.text = getString(R.string.upload_successful)

            // Progress bar appears after confirming
            val uploadProgressBar = findViewById<ProgressBar>(R.id.uploadProgressBar)
            uploadProgressBar.visibility = View.VISIBLE
            uploadProgressBar.progress = 0

            CoroutineScope(Dispatchers.IO).launch {
              UploadService.pauseUploadUntil(null)
              try {
                updateConnectionUi()

                val uploadSucceeded = dataManager.uploadData { progress ->
                  runOnUiThread {
                    Log.d(TAG, "Updating ProgressBar to $progress%")
                    uploadProgressBar.progress = progress
                  }
                }

                runOnUiThread {
                  uploadButton.isEnabled = true
                  uploadButton.isClickable = true
                  if (uploadSucceeded) {
                    uploadButton.text = "Upload Now"
                  } else {
                    uploadButton.text = "Upload Failed, Click to try again"
                    val textFinish = "Upload Failed"
                    val toastFinish = Toast.makeText(applicationContext, textFinish, Toast.LENGTH_LONG)
                    toastFinish.show()
                  }
                }
              } catch (e: InterruptedUploadException) {
                Log.w(TAG, "Upload Data was interrupted.")
                runOnUiThread {
                  val textFinish = "Upload interrupted"
                  val toastFinish = Toast.makeText(applicationContext, textFinish, Toast.LENGTH_LONG)
                  toastFinish.show()
                  uploadButton.isEnabled = true
                  uploadButton.isClickable = true
                  uploadButton.text = "Upload Now"
                }
              }
              updateConnectionUi()
            }
          }

          setNegativeButton("No") { dialog, _ ->
            Log.i(TAG, "User canceled upload.")
            dialog.dismiss()
          }
        }

        val dialog = builder.create()
        dialog.apply {
          setCanceledOnTouchOutside(true)
          setOnCancelListener {
            Log.i(TAG, "User dismissed the upload confirmation dialog.")
          }
          show()
        }
      }
    }
  }

  fun lifetimeMSTimeFormatter(milliseconds: Long): String {
    val min = milliseconds / 60000
    val sec = (milliseconds % 60000) / 1000
    val msec = milliseconds % 1000
    val formattedTime = String.format("%02dm %02ds %03dms", min, sec, msec)
    return formattedTime
  }

  /**
   * onCreate() function from Activity - called when the home screen activity is launched.
   */
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    dataManager = DataManager(applicationContext)

    // Start the UploadService (which should already be running anyway).
    applicationContext.startForegroundService(Intent(applicationContext, UploadService::class.java))
    // Load UI from XML
    val binding = ActivitySplashBinding.inflate(layoutInflater)
    val view = binding.root
    setContentView(view)

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

    runBlocking {
      val keyObject = booleanPreferencesKey("permissionRequestedPreviously")
      permissionRequestedPreviously = applicationContext.prefStore.data
        .map {
          it[keyObject]
        }.firstOrNull() ?: false
    }

    val titleText = findViewById<TextView>(R.id.header)
    var numTitleClicks = 0
    titleText.setOnClickListener {
      numTitleClicks += 1
      if (numTitleClicks == 5) {
        numTitleClicks = 0
        val intent = Intent(this, LoadDataActivity::class.java)
        startActivity(intent)
      }
    }
    titleText.isSoundEffectsEnabled = false
  }

  /**
   * onResume() function from Activity - used when opening the app from multitasking
   */
  override fun onResume() {
    super.onResume()
    Log.d(TAG, "Recording sessions in current sitting: $currentRecordingSessions")
    if (currentRecordingSessions >= MAX_RECORDINGS_IN_SITTING) {
      setContentView(R.layout.end_of_sitting_message)
      return
    }
    setupLoadingUI()
    thread {
      runBlocking {
        username = dataManager.getUsername()
        prompts = dataManager.getPrompts()
        if (username == null) {
          val intent = Intent(applicationContext, LoadDataActivity::class.java)
          startActivity(intent)
        }
        deviceId = dataManager.getDeviceId()
        val numPrompts = prompts?.array?.size
        val promptIndex = prompts?.promptIndex
        dataManager.logToServer(
          "Started Application phoneId=${deviceId} username=${username} promptIndex=${promptIndex} numPrompts=${numPrompts}"
        )

        setupUI()
      }
    }
  }

}
