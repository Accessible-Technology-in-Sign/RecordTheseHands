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

import android.Manifest
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.databinding.ActivityLoadDataBinding
import edu.gatech.ccg.recordthesehands.hapticFeedbackOnTouchListener
import edu.gatech.ccg.recordthesehands.upload.DataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

class LoadDataActivity : ComponentActivity() {
  companion object {
    private val TAG = LoadDataActivity::class.simpleName
  }

  lateinit var dataManager: DataManager

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val binding = ActivityLoadDataBinding.inflate(layoutInflater)
    setContentView(binding.root)

    requestAllPermissions()

    val deviceIdText = findViewById<TextView>(R.id.setDeviceIdText)
    val usernameText = findViewById<TextView>(R.id.usernameTextField)

    dataManager = DataManager(applicationContext)
    runBlocking {
      val deviceId = dataManager.getDeviceId()
      deviceIdText.hint = deviceId
    }

    val username = dataManager.getUsername()
    if (username != null) {
      usernameText.hint = username
    }

    val setDeviceIdButton = findViewById<Button>(R.id.setDeviceIdButton)
    setDeviceIdButton.setOnTouchListener(::hapticFeedbackOnTouchListener)
    setDeviceIdButton.setOnClickListener {
      val newDeviceId = deviceIdText.text.toString().trim()
      CoroutineScope(Dispatchers.IO).launch {
        dataManager.setDeviceId(newDeviceId)
      }
    }

    val createAccountButton = findViewById<Button>(R.id.createAccountButton)

    createAccountButton.setOnTouchListener(::hapticFeedbackOnTouchListener)
    createAccountButton.setOnClickListener {
      val username = usernameText.text.toString()
      createAccountButton.isEnabled = false
      createAccountButton.isClickable = false
      createAccountButton.text = "Creating account."
      val adminPassword = findViewById<EditText>(R.id.adminPasswordTextField).text.toString()
      lifecycleScope.launch {
        thread {  // Don't run network on UI thread.
          val result = dataManager.createAccount(username, adminPassword)
          runOnUiThread {
            AlertDialog.Builder(this@LoadDataActivity).apply {
              if (result) {
                setTitle("Success")
                setMessage("Created account for \"$username\" and stored credentials.")
                createAccountButton.isEnabled = true
                createAccountButton.isClickable = true
                createAccountButton.text = "Create account"
              } else {
                setTitle("Failed")
                setMessage("Failed to Create account for \"$username\".")
                createAccountButton.isEnabled = true
                createAccountButton.isClickable = true
                createAccountButton.text = "Create account failed, try again"
              }
              setPositiveButton("OK") { _, _ -> }
              create()
              show()
            }
          }
        }
      }
    }

    val enableTutorialModeButton = findViewById<Button>(R.id.enableTutorialModeButton)
    enableTutorialModeButton.setOnTouchListener(::hapticFeedbackOnTouchListener)
    enableTutorialModeButton.setOnClickListener {
      CoroutineScope(Dispatchers.IO).launch {
        dataManager.setTutorialMode(true)
        dataManager.saveCurrentPromptIndex(0)
        finish()
      }
    }

  }

  fun requestAllPermissions() {
    val launcher = registerForActivityResult(
      ActivityResultContracts.RequestMultiplePermissions()
    ) { map ->
      val cameraGranted = map[Manifest.permission.CAMERA] ?: false
      runOnUiThread {
        val text = "Permissions: camera $cameraGranted"
        val toast = Toast.makeText(applicationContext, text, Toast.LENGTH_LONG)
        toast.show()
      }
    }
    launcher.launch(arrayOf(Manifest.permission.CAMERA))
  }

}
