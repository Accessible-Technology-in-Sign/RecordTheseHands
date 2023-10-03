/**
 * HomeScreenActivity.kt
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
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.accounts.AccountManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import edu.gatech.ccg.recordthesehands.*
import edu.gatech.ccg.recordthesehands.Constants.APP_VERSION
import edu.gatech.ccg.recordthesehands.Constants.MAX_RECORDINGS_IN_SITTING
import edu.gatech.ccg.recordthesehands.Constants.PERMIT_CUSTOM_PHRASE_LOADING
import edu.gatech.ccg.recordthesehands.Constants.RECORDINGS_PER_WORD
import edu.gatech.ccg.recordthesehands.Constants.RESULT_CAMERA_DIED
import edu.gatech.ccg.recordthesehands.Constants.RESULT_NO_ERROR
import edu.gatech.ccg.recordthesehands.Constants.RESULT_RECORDING_DIED
import edu.gatech.ccg.recordthesehands.Constants.WORDS_PER_SESSION
import edu.gatech.ccg.recordthesehands.databinding.ActivitySplashBinding
import edu.gatech.ccg.recordthesehands.recording.RecordingActivity
import edu.gatech.ccg.recordthesehands.upload.DataManager
import edu.gatech.ccg.recordthesehands.upload.UploadService
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.launch

/**
 * The home page for the app. The user can see statistics and start recording from this page.
 */
class HomeScreenActivity : ComponentActivity() {

  companion object {
    private val TAG = HomeScreenActivity::class.simpleName
  }

  // UI elements

  /**
   * A display for the user's UID.
   */
  lateinit var uidBox: TextView

  /**
   * A text box showing the user's five most-recorded words.
   */
  lateinit var statsWordList: TextView

  /**
   * A text box showing the corresponding counts for each of the five words.
   */
  lateinit var statsWordCounts: TextView

  /**
   * A text box showing the total number of recordings the user has done across all words.
   */
  lateinit var recordingCount: TextView

  /**
   * A text box previewing the words the user will sign in the next session.
   */
  lateinit var nextSessionWords: TextView

  /**
   * A button to select a new set of random words for the next session.
   */
  private lateinit var randomizeButton: Button

  /**
   * The button the user presses to enter the recording session.
   */
  private lateinit var startRecordingButton: Button

  // State elements

  /**
   * The user's ID, which will be attached to their files and sent with their
   * confirmation emails.
   */
  private var uid = ""

  /**
   * The list of words selected for the next recording session.
   */
  private lateinit var selectedWords: ArrayList<String>

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
  private var hasRequestedPermission: Boolean = false

  /**
   * Gets the global preferences for the device. We use this to get the user's
   * UID.
   */
  private lateinit var globalPrefs: SharedPreferences

  /**
   * Gets the local preferences for the application. We use this to store how many
   * times the user has recorded each word.
   */
  private lateinit var localPrefs: SharedPreferences

  /**
   * A list of all 250 words that we allow recording
   */
  lateinit var allWords: ArrayList<String>

  /**
   * A list of how many times each word has been recorded. We use this to preferentially
   * select the least-recorded words for the next session.
   */
  lateinit var recordingCounts: ArrayList<Int>

  /**
   * The total number of recordings the user has done across all sessions.
   */
  private var lifetimeRecordingCount = 0

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
      when (result.resultCode) {
        RESULT_NO_ERROR -> {
          currentRecordingSessions += 1
        }

        RESULT_CAMERA_DIED, RESULT_RECORDING_DIED -> {
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
      val writeStorage = map[WRITE_EXTERNAL_STORAGE] ?: false

      if (accessCamera && writeStorage) {
        // Permission is granted.
        val intent = Intent(this, RecordingActivity::class.java).apply {
          putStringArrayListExtra("WORDS", selectedWords)
          putExtra("UID", uid)
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
   * Counts the number of recordings the user has done, and updates the UI accordingly.
   */
  private fun updateCounts() {
    allWords = ArrayList(listOf(*resources.getStringArray(R.array.all)))

    val customPhrases = localPrefs.getStringSet("customPhrases", HashSet<String>())!!
    allWords.addAll(customPhrases.toList())

    recordingCounts = ArrayList()
    lifetimeRecordingCount = 0

    val showableWords = ArrayList<Pair<Int, String>>()

    for (word in allWords) {
      // Get the stored recording count for each word, or default to 0
      val count = localPrefs.getInt("RECORDING_COUNT_$word", 0)
      recordingCounts.add(count)
      lifetimeRecordingCount += count

      // A word should only be shown as recorded on the splash screen if the user
      // has recorded it at least once.
      showableWords.add(Pair(count, word))
    }

    // Sort words by the number of recordings (most to least), then alphabetically.
    showableWords.sortWith(
      compareByDescending<Pair<Int, String>> { it.first }.thenBy { it.second }
    )

    // If the least recorded word has been recorded at least `RECORDINGS_PER_WORD` times,
    // then the user has done all the recordings they need to! Show an alert accordingly.
    if (showableWords.isNotEmpty() && showableWords.last().first >= RECORDINGS_PER_WORD) {
      val builder = AlertDialog.Builder(this).apply {
        setTitle("\uD83C\uDF89 Congratulations, you've finished recording!")
        setMessage("If you'd like to record more phrases, click the button below.")

        val input = EditText(context)
        setView(input)

        setPositiveButton("I'd like to keep recording") { dialog, _ ->
          dialog.dismiss()
        }
      }

      builder.create().show()
    }

    // Show up to 5 words' counts here
    val statsWordCount = min(showableWords.size, 5)

    /**
     * wcText: word counts, wlText: word labels
     * We need two text elements here, as this information is displayed in a table-like format:
     *
     * hello       5 times
     * world       3 times
     * foo         2 times
     * bar         1 time
     * etc.
     */
    var wcText = ""
    var wlText = ""
    for (i in 0 until statsWordCount) {
      val pair = showableWords[i]
      wlText += "\n" + pair.second
      wcText += "\n" + pair.first + (if (pair.first == 1) " time" else " times")
    }

    // Set UI elements according to the results
    statsWordList = findViewById(R.id.statsWordList)
    statsWordCounts = findViewById(R.id.statsWordCounts)

    recordingCount = findViewById(R.id.recordingCount)

    if (wlText.isNotEmpty()) {
      // substring(1) to trim the first \n added to each string above
      statsWordList.text = wlText.substring(1)
      statsWordCounts.text = wcText.substring(1)
    } else {
      statsWordList.text = "No recordings yet!"
      statsWordCounts.text = ""
    }

    val goal = allWords.size * RECORDINGS_PER_WORD
    recordingCount.text = "$lifetimeRecordingCount recordings completed (out of $goal)"

    val sessionCounter = findViewById<TextView>(R.id.sessionCounter)
    val count = currentRecordingSessions
    val pluralize = if (count == 1) "session" else "sessions"
    sessionCounter.text = "You've completed $count $pluralize in this sitting! Once you " +
        "finish $MAX_RECORDINGS_IN_SITTING sessions, you can either come back later or " +
        "restart the app to continue recording."
  } // updateCounts()

  /**
   * Sets up all of the UI elements.
   */
  private fun setupUI() {
    updateCounts()

    // Get 10 random words and set the randomizeButton to reroll the 10 selected
    // words when it is pressed.
    getRandomWords(allWords, recordingCounts)
    randomizeButton = findViewById(R.id.rerollButton)
    randomizeButton.setOnClickListener {
      getRandomWords(allWords, recordingCounts)
    }


    startRecordingButton = findViewById(R.id.startButton)
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
      Log.d(TAG, "Storage allowed: ${checkPermission(WRITE_EXTERNAL_STORAGE)}")
      Log.d(TAG, "Ask for camera permission: ${shouldAsk(CAMERA)}")
      Log.d(TAG, "Ask for storage permission: ${shouldAsk(WRITE_EXTERNAL_STORAGE)}")

      // check permissions here
      when {
        // User has granted all necessary permissions
        (checkPermission(CAMERA) && checkPermission(WRITE_EXTERNAL_STORAGE)) -> {
          // You can use the API that requires the permission.
          val intent = Intent(this, RecordingActivity::class.java).apply {
            putStringArrayListExtra("WORDS", selectedWords)
            putExtra("UID", uid)
            putExtra("SEND_CONFIRMATION_EMAIL", emailing)
          }

          handleRecordingResult.launch(intent)
        }

        // We've asked the user for permissions before, they haven't been granted,
        // and we cannot ask the user for either camera or storage permissions (we already
        // asked them before)
        hasRequestedPermission && ((cannotGetPermission(CAMERA)) ||
            cannotGetPermission(WRITE_EXTERNAL_STORAGE)) -> {

          val text = "Please enable camera and storage access in Settings"
          val toast = Toast.makeText(this, text, Toast.LENGTH_LONG)
          toast.show()
        }

        // We've asked the user for permissions before, and the prior `when` case failed,
        // so we are allowed to ask for at least one of the required permissions
        hasRequestedPermission && ((shouldAsk(CAMERA)) ||
            shouldAsk(WRITE_EXTERNAL_STORAGE)) -> {
          // Send an alert prompting the user that they need to grant permissions
          val builder = AlertDialog.Builder(this).apply {
            setTitle("Permissions are required to use the app")
            setMessage(
              "In order to record your data, we will need access to " +
                  "the camera and write functionality."
            )

            setPositiveButton("OK") { dialog, _ ->
              requestRecordingPermissions.launch(
                arrayOf(CAMERA, WRITE_EXTERNAL_STORAGE)
              )
              dialog.dismiss()
            }

          }

          val dialog = builder.create()
          dialog.apply {
            setCanceledOnTouchOutside(true)
            setOnCancelListener {
              requestRecordingPermissions.launch(
                arrayOf(CAMERA, WRITE_EXTERNAL_STORAGE)
              )
            }
            show()
          }
        }

        // No permissions, and we haven't asked for permissions before
        else -> {
          if (!hasRequestedPermission) {
            hasRequestedPermission = true
            with(globalPrefs.edit()) {
              putBoolean("hasRequestedPermission", true)
              apply()
            }
          }
          requestRecordingPermissions.launch(arrayOf(CAMERA, WRITE_EXTERNAL_STORAGE))
        }
      } // when
    } // setRecordingButton.onClickListener
  } // setupUI()

  /**
   * onCreate() function from Activity - called when the home screen activity is launched.
   */
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

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
    localPrefs = getSharedPreferences("app_settings", MODE_PRIVATE)

    hasRequestedPermission = globalPrefs.getBoolean("hasRequestedPermission", false)

    if (globalPrefs.getString("UID", "")!!.isNotEmpty()) {
      this.uid = globalPrefs.getString("UID", "")!!
    } else {
      // Check for permission to read the phone's primary signed-in gmail username
      requestUsernamePermissions.launch(arrayOf(GET_ACCOUNTS, READ_CONTACTS))
      val manager = AccountManager.get(this)
      val accountList = manager.getAccountsByType("com.google")
      Log.d(TAG, "Google accounts: ${accountList.joinToString(", ")}")
      if (accountList.isNotEmpty()) {
        this.uid = accountList[0].name.split("@")[0]
        with(globalPrefs.edit()) {
          putString("UID", uid)
          apply()
        }
      } else {
        // Assign a UID at random
        this.uid = abs(Random.nextLong()).toString()
        Log.w(
          TAG, "Account not found (permission not granted or user not signed " +
              "in): assigned UID '$uid' at random"
        )
      }
    }

    // The default value for the `phrasesLoaded` internal setting is the opposite of
    // PERMIT_CUSTOM_PHRASE_LOADING. If PERMIT_CUSTOM_PHRASE_LOADING is false, then we should
    // not show the "Load Phrases" button. Hence the expression below would evaluate to
    // !!false (= false). Of course, if the `phrasesLoaded` value has been set, then we should
    // not show the "Load Phrases" button.
    if (!localPrefs.getBoolean("phrasesLoaded", !PERMIT_CUSTOM_PHRASE_LOADING)) {
      val loadPhrasesButton = findViewById<Button>(R.id.loadPhrasesButton)
      loadPhrasesButton.visibility = View.VISIBLE

      val pickFile = registerForActivityResult(GetContent()) {
        contentResolver.openInputStream(it)?.use { stream ->
          val text = stream.bufferedReader().use { reader -> reader.readText() }
          if (text.isEmpty()) {
            AlertDialog.Builder(this).apply {
              setTitle("Loading phrases failed")
              setMessage("File provided was empty")
              setPositiveButton("OK") { _, _ -> }
              create()
              show()
            }
          }

          val phrases = text.split("\n")
          val phraseSet = phrases.toSet()

          with(localPrefs.edit()) {
            putBoolean("phrasesLoaded", true)
            putStringSet("customPhrases", phraseSet)
            apply()

            runOnUiThread {
              loadPhrasesButton.visibility = View.INVISIBLE
              updateCounts()

              AlertDialog.Builder(this@HomeScreenActivity).apply {
                setTitle("Success!")
                setMessage("Loaded ${phraseSet.size} custom phrases")
                setPositiveButton("OK") { _, _ -> }
                create()
                show()
              }
            }
          }
        } ?: run {
          AlertDialog.Builder(this).apply {
            setMessage("Could not load the file!")
            create()
            show()
          }
        }
      }
      loadPhrasesButton.setOnClickListener {
        pickFile.launch("text/plain")
      }

      val createAccountButton = findViewById<Button>(R.id.createAccountButton)

      createAccountButton.setOnClickListener {
        val username = findViewById<EditText>(R.id.usernameTextField).text.toString()
        val adminPassword = findViewById<EditText>(R.id.adminPasswordTextField).text.toString()
        lifecycleScope.launch {
          val dataManager = DataManager(applicationContext)
          thread {
            val result = dataManager.createAccount(username, adminPassword)
            runOnUiThread {
              AlertDialog.Builder(this@HomeScreenActivity).apply {
                if (result) {
                  setTitle("Success")
                  setMessage("Created account for \"$username\" and stored credentials.")
                } else {
                  setTitle("Failed")
                  setMessage("Failed to Create account for \"$username\".")
                }
                setPositiveButton("OK") { _, _ -> }
                create()
                show()
              }
            }
          }
        }
      }

    }

    uidBox = findViewById(R.id.uidBox)
    uidBox.text = this.uid

    val versionText = findViewById<TextView>(R.id.versionText)
    versionText.text = "v$APP_VERSION"

    setupUI()
  } // onCreate()

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

  /**
   * Helper function to choose a set of random words and put them into the UI element
   * that shows the user which words will be part of the next recording session.
   */
  private fun getRandomWords(wordList: ArrayList<String>, recordingCounts: ArrayList<Int>) {
    selectedWords = lowestCountRandomChoice(wordList, recordingCounts, WORDS_PER_SESSION)

    nextSessionWords = findViewById(R.id.recordingListColumn1)
    nextSessionWords.text = selectedWords.subList(0, 5).joinToString("\n") {
      clipText(it)
    }

    nextSessionWords = findViewById(R.id.recordingListColumn2)
    nextSessionWords.text = selectedWords.subList(5, 10).joinToString("\n") {
      clipText(it)
    }
  }

}
