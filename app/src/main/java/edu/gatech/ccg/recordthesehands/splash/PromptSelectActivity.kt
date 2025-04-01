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