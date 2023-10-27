/**
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
package edu.gatech.ccg.recordthesehands.splash

import android.Manifest.permission.CAMERA
import android.Manifest.permission.GET_ACCOUNTS
import android.Manifest.permission.READ_CONTACTS
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.lifecycleScope
import edu.gatech.ccg.recordthesehands.*
import edu.gatech.ccg.recordthesehands.Constants.APP_VERSION
import edu.gatech.ccg.recordthesehands.Constants.MAX_RECORDINGS_IN_SITTING
import edu.gatech.ccg.recordthesehands.databinding.ActivitySplashBinding
import edu.gatech.ccg.recordthesehands.recording.RecordingActivity
import edu.gatech.ccg.recordthesehands.upload.DataManager
import edu.gatech.ccg.recordthesehands.upload.UploadService
import edu.gatech.ccg.recordthesehands.upload.prefStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlin.concurrent.thread
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The home page for the app. The user can see statistics and start recording from this page.
 */
class HomeScreenActivity : ComponentActivity() {

  companion object {
    private val TAG = HomeScreenActivity::class.simpleName
  }

  // UI elements

  /**
   * The button the user presses to enter the recording session.
   */
  private lateinit var startRecordingButton: Button

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
   * Gets the global preferences for the device. We use this to get the user's
   * UID.
   */
  private lateinit var globalPrefs: SharedPreferences

  /**
   * The total number of recordings the user has done in the current session (i.e., since they
   * last cold-booted the app). After this value reaches [Constants.MAX_RECORDINGS_IN_SITTING],
   * we ask the user to fully close and relaunch the app. This is in place as a quick-hack
   * solution for occasional memory leaks and crashes that occurred when the app was left running
   * for too long without a restart.
   */
  private var currentRecordingSessions = 0

  /**
   * Check permissions necessary to fetch the user's UID
   */
  private val requestUsernamePermissions =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
      val getAccounts = map[GET_ACCOUNTS] ?: false
      val readContacts = map[READ_CONTACTS] ?: false
      if (!getAccounts || !readContacts) {
        // Permission is not granted.
        val text = "Cannot assign UID since permissions not granted"
        val toast = Toast.makeText(this, text, Toast.LENGTH_SHORT)
        toast.show()
      }
    }

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
        val intent = Intent(this, RecordingActivity::class.java).apply {
          putExtra("SEND_CONFIRMATION_EMAIL", emailing)
        }

        handleRecordingResult.launch(intent)
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
  private fun setupUI() {
    startRecordingButton = findViewById(R.id.startButton)
    startRecordingButton.isEnabled = false
    startRecordingButton.isClickable = false

    lifecycleScope.launch {
      val prompts = dataManager.getPrompts()
      val username = dataManager.getUsername()
      val uid = dataManager.getPhoneId()
      val uidBox = findViewById<TextView>(R.id.uidBox)
      if (username != null) {
        uidBox.text = username
      } else {
        uidBox.text = uid
      }
      if (prompts != null && username != null) {
        startRecordingButton.isEnabled = true
        startRecordingButton.isClickable = true
      }
      var keyObject = stringPreferencesKey("lifetimeRecordingCount")
      var lifetimeRecordingCount = applicationContext.prefStore.data
        .map {
          it[keyObject]?.toLong()
        }.firstOrNull() ?: 0L
      val recordingCountBox = findViewById<TextView>(R.id.recordingCountBox)
      recordingCountBox.text = lifetimeRecordingCount.toString()
      keyObject = stringPreferencesKey("lifetimeRecordingMs")
      var lifetimeRecordingMs = applicationContext.prefStore.data
        .map {
          it[keyObject]?.toLong()
        }.firstOrNull() ?: 0L
      val recordingTimeBox = findViewById<TextView>(R.id.recordingTimeBox)
      recordingTimeBox.text = msToHMS(lifetimeRecordingMs)
      val sessionCounterBox = findViewById<TextView>(R.id.sessionCounterBox)
      sessionCounterBox.text = currentRecordingSessions.toString()
      if (prompts != null) {
        val completedPromptsBox = findViewById<TextView>(R.id.completedPromptsBox)
        completedPromptsBox.text = prompts.promptIndex.toString()
        val totalPromptsBox = findViewById<TextView>(R.id.totalPromptsBox)
        totalPromptsBox.text = prompts.array.size.toString()
      }
    }

    startRecordingButton.setOnTouchListener(::hapticFeedbackOnTouchListener)
    startRecordingButton.setOnClickListener {
      fun checkPermission(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(this, perm) ==
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

      // check permissions here
      when {
        // User has granted all necessary permissions
        checkPermission(CAMERA) -> {
          // You can use the API that requires the permission.
          val intent = Intent(this, RecordingActivity::class.java).apply {
            putExtra("SEND_CONFIRMATION_EMAIL", emailing)
          }

          handleRecordingResult.launch(intent)
        }

        // We've asked the user for permissions before, they haven't been granted,
        // and we cannot ask the user for either camera or storage permissions (we already
        // asked them before)
        permissionRequestedPreviously && cannotGetPermission(CAMERA) -> {

          val text = "Please enable camera and storage access in Settings"
          val toast = Toast.makeText(this, text, Toast.LENGTH_LONG)
          toast.show()
        }

        // We've asked the user for permissions before, and the prior `when` case failed,
        // so we are allowed to ask for at least one of the required permissions
        permissionRequestedPreviously && shouldAsk(CAMERA) -> {
          // Send an alert prompting the user that they need to grant permissions
          val builder = AlertDialog.Builder(this).apply {
            setTitle("Permissions are required to use the app")
            setMessage(
              "In order to record your data, we will need access to " +
                  "the camera and write functionality."
            )

            setPositiveButton("OK") { dialog, _ ->
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
        }

        else -> {
          // No permissions, and we haven't asked for permissions before
          if (!permissionRequestedPreviously) {
            permissionRequestedPreviously = true
            with(globalPrefs.edit()) {
              putBoolean("permissionRequestedPreviously", true)
              apply()
            }
          }
          requestRecordingPermissions.launch(arrayOf(CAMERA))
        }
      } // when
    } // setRecordingButton.onClickListener
  } // setupUI()

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

    globalPrefs = getPreferences(MODE_PRIVATE)

    permissionRequestedPreviously = globalPrefs.getBoolean("permissionRequestedPreviously", false)

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

    thread {
      runBlocking {
        val username = dataManager.getUsername()
        val prompts = dataManager.getPrompts()
        if (username == null || prompts == null) {
          val intent = Intent(applicationContext, LoadDataActivity::class.java)
          startActivity(intent)
        }
        val uid = dataManager.getPhoneId()
        val numPrompts = prompts?.array?.size
        val promptIndex = prompts?.promptIndex
        dataManager.logToServer(
          "Started Application phoneId=${uid} username=${username} promptIndex=${promptIndex} numPrompts=${numPrompts}"
        )
      }
    }
    val versionText = findViewById<TextView>(R.id.versionText)
    versionText.text = "v$APP_VERSION"

    setupUI()
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

    setupUI()
  }

}
