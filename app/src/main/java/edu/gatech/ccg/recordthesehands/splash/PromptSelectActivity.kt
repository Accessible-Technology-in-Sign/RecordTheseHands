/**
 * This file is part of Record These Hands, licensed under the MIT license.
 *
 * Copyright (c) 2025
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

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_RESUME_ON_IDLE_TIMEOUT
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.databinding.ActivityPromptPickerBinding
import edu.gatech.ccg.recordthesehands.hapticFeedbackOnTouchListener
import edu.gatech.ccg.recordthesehands.upload.DataManager
import edu.gatech.ccg.recordthesehands.upload.PromptState
import edu.gatech.ccg.recordthesehands.upload.UploadService
import kotlinx.coroutines.launch

class PromptSelectActivity : ComponentActivity() {

  private var windowInsetsController: WindowInsetsControllerCompat? = null

  companion object {
    private val TAG = PromptSelectActivity::class.simpleName
  }

  private lateinit var dataManager: DataManager
  private lateinit var binding: ActivityPromptPickerBinding

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    windowInsetsController =
      WindowCompat.getInsetsController(window, window.decorView)?.also {
        it.systemBarsBehavior =
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }

    binding = ActivityPromptPickerBinding.inflate(layoutInflater)
    setContentView(binding.root)
    dataManager = DataManager(applicationContext)

    // Observe the overall prompt state
    dataManager.promptState.observe(this) { state ->
      if (state == null) return@observe
      updateTutorialButton(state.tutorialMode)
      populateSections(state)
    }

    binding.toggleTutorialButton.setOnTouchListener(::hapticFeedbackOnTouchListener)
    binding.toggleTutorialButton.setOnClickListener {
      Log.d(TAG, "Toggle Tutorial Mode button pressed.")
      lifecycleScope.launch {
        dataManager.toggleTutorialMode()
        finish() // Return to HomeScreen
      }
    }
  }

  private fun updateTutorialButton(isTutorialMode: Boolean) {
    binding.toggleTutorialButton.text = if (isTutorialMode) {
      getString(R.string.switch_to_tutorial_prompts)
    } else {
      getString(R.string.switch_to_tutorial_prompts)
    }
  }

  private fun populateSections(state: PromptState) {
    binding.promptSectionsLayout.removeAllViews()
    val sections = state.promptsCollection?.sections ?: return

    for (sectionName in sections.keys.sorted()) {
      val section = sections[sectionName]!!
      val prompts = section.mainPrompts
      val total = prompts.array.size
      val sectionProgress = state.promptProgress[sectionName]
      val completed = sectionProgress?.get("mainIndex") ?: 0
      val isCompleted = total > 0 && completed >= total

      val sectionView =
        layoutInflater.inflate(R.layout.section_list_item, binding.promptSectionsLayout, false)

      val sectionButton = sectionView.findViewById<Button>(R.id.sectionButton)
      val progressText = sectionView.findViewById<TextView>(R.id.progressText)

      sectionButton.text = sectionName
      progressText.text = getString(R.string.prompts_completed_progress, completed, total)
      sectionButton.isEnabled = !isCompleted || state.tutorialMode

      if (sectionButton.isEnabled) {
        sectionButton.setOnClickListener {
          lifecycleScope.launch {
            Log.d(TAG, "Clicked on button to set Prompts Section to ${sectionName}")
            UploadService.pauseUploadTimeout(UPLOAD_RESUME_ON_IDLE_TIMEOUT)
            dataManager.resetToSection(sectionName)
            finish() // Return to HomeScreen
          }
        }
      }
      binding.promptSectionsLayout.addView(sectionView)
    }
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
