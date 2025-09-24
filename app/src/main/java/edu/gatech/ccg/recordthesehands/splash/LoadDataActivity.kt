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
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.databinding.ActivityLoadDataBinding
import edu.gatech.ccg.recordthesehands.hapticFeedbackOnTouchListener
import edu.gatech.ccg.recordthesehands.upload.DataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

class LoadDataActivity : AppCompatActivity() {
  companion object {
    private val TAG = LoadDataActivity::class.simpleName
  }

  lateinit var dataManager: DataManager
  private lateinit var binding: ActivityLoadDataBinding

  private fun clearTextFocus() {
    Log.d(TAG, "window.currentFocus = ${window.currentFocus.toString()}")
    val focused = currentFocus
    focused?.clearFocus()
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(focused?.windowToken, 0)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    binding = ActivityLoadDataBinding.inflate(layoutInflater)
    setContentView(binding.root)

    // Unfocus keyboard when background is touched.
    binding.root.setOnTouchListener { view, _ ->
      clearTextFocus()
      false // Do not consume the event.
    }

    requestAllPermissions()

    dataManager = DataManager(applicationContext)
    dataManager.promptState.observe(this) { state ->
      val deviceId = state?.deviceId ?: "Unknown Device Id"
      val username = state?.username ?: getString(R.string.unknown_username)

      binding.deviceIdLabel.text = getString(R.string.device_id_with_current, deviceId)
      binding.usernameLabel.text = getString(R.string.username_label_with_current, username)
    }

    binding.setDeviceIdButton.setOnTouchListener(::hapticFeedbackOnTouchListener)
    binding.setDeviceIdButton.setOnClickListener {
      val newDeviceId = binding.setDeviceIdText.text.toString().trim()
      clearTextFocus()
      CoroutineScope(Dispatchers.IO).launch {
        dataManager.setDeviceId(newDeviceId)
      }
    }

    binding.createAccountButton.setOnTouchListener(::hapticFeedbackOnTouchListener)
    binding.createAccountButton.setOnClickListener {
      clearTextFocus()
      val username = binding.usernameTextField.text.toString()
      binding.createAccountButton.isEnabled = false
      binding.createAccountButton.isClickable = false
      binding.createAccountButton.text = "Creating account."
      val adminPassword = binding.adminPasswordTextField.text.toString()
      lifecycleScope.launch {
        thread {  // Don't run network on UI thread.
          // TODO Figure out a better concurrency model.  createAccount could benefit from being
          // suspending.
          val result = dataManager.createAccount(username, adminPassword)
          runOnUiThread {
            AlertDialog.Builder(this@LoadDataActivity).apply {
              if (result) {
                setTitle("Success")
                setMessage("Created account for \"$username\" and stored credentials.")
                binding.createAccountButton.isEnabled = true
                binding.createAccountButton.isClickable = true
                binding.createAccountButton.text = "Create account"
              } else {
                setTitle("Failed")
                setMessage("Failed to Create account for \"$username\".")
                binding.createAccountButton.isEnabled = true
                binding.createAccountButton.isClickable = true
                binding.createAccountButton.text = "Create account failed, try again"
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

  fun requestAllPermissions() {
    val launcher = registerForActivityResult(
      ActivityResultContracts.RequestMultiplePermissions()
    ) { map ->
      val cameraGranted = map[Manifest.permission.CAMERA] ?: false
      runOnUiThread {
        val text = "Permissions: camera $cameraGranted"
        Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
      }
    }
    launcher.launch(arrayOf(Manifest.permission.CAMERA))
  }

}