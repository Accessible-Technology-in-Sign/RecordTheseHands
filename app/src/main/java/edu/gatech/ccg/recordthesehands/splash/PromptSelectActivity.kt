package edu.gatech.ccg.recordthesehands.splash

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.databinding.ActivityPromptPickerBinding
import edu.gatech.ccg.recordthesehands.databinding.ActivitySplashBinding
import edu.gatech.ccg.recordthesehands.hapticFeedbackOnTouchListener
import edu.gatech.ccg.recordthesehands.splash.HomeScreenActivity.Companion
import edu.gatech.ccg.recordthesehands.upload.DataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PromptSelectActivity : ComponentActivity() {

  companion object {
    private val TAG = PromptSelectActivity::class.simpleName
  }

  private lateinit var dataManager: DataManager

  // TODO: Separate statistics for tutorial and loaded prompts.

  // TODO: Need variables to keep track of session completion count for both tutorial and loaded prompts when back arrowing to selection page

  // TODO: Adapt updateConnectionUi() function in HomeScreenActivity.kt for this class

  private fun setupUI() {
    lifecycleScope.launch {
      val backArrow = findViewById<ImageButton>(R.id.backButton)
      backArrow.setOnClickListener {
        CoroutineScope(Dispatchers.IO).launch {
          dataManager.setTutorialMode(false)
          dataManager.getPrompts()?.also {
            it.promptIndex = 0
            it.savePromptIndex()
          }

          // startActivity is configured so that it will not run on anything but the main thread. So, this will create the intent and start it on the main thread.
          withContext(Dispatchers.Main) {
            val intent = Intent(this@PromptSelectActivity, LoadDataActivity::class.java)
            startActivity(intent)
            Log.i(TAG, "Logging out")
            // TODO: Implement a proper logout system
//            dataManager.deleteLoginToken()
            finish()
          }
        }
      }

      val loadedPrompts = findViewById<Button>(R.id.loadedPrompts)
      loadedPrompts.setOnTouchListener(::hapticFeedbackOnTouchListener)
      loadedPrompts.setOnClickListener {
        CoroutineScope(Dispatchers.IO).launch {
            dataManager.setTutorialMode(false)
            dataManager.getPrompts()?.also {
              it.promptIndex = 0
              it.savePromptIndex()
          }
          withContext(Dispatchers.Main) {
            val intent = Intent(this@PromptSelectActivity, HomeScreenActivity::class.java)
            startActivity(intent)
            Log.i(TAG, "Moving from prompt selector to loaded prompts")
            finish()
          }
        }
      }

      val tutorialPrompts = findViewById<Button>(R.id.tutorialModePrompts)
      tutorialPrompts.setOnTouchListener(::hapticFeedbackOnTouchListener)
      tutorialPrompts.setOnClickListener {
        CoroutineScope(Dispatchers.IO).launch {
          dataManager.setTutorialMode(true)
          dataManager.getPrompts()?.also {
            it.promptIndex = 0
            it.savePromptIndex()
          }
          withContext(Dispatchers.Main) {
            val intent = Intent(this@PromptSelectActivity, HomeScreenActivity::class.java)
            startActivity(intent)
            Log.i(TAG, "Moving from prompt selector to tutorial prompts")
            finish()
          }
        }
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    dataManager = DataManager(applicationContext)
    val binding = ActivityPromptPickerBinding.inflate(layoutInflater)
    val view = binding.root
    setContentView(view)
    setupUI()
  }
}