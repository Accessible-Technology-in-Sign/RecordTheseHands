/**
 * This file is part of Record These Hands, licensed under the MIT license.
 *
 * Copyright (c) 2023-2025
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
package edu.gatech.ccg.recordthesehands.upload

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileNotFoundException

class DataManagerData private constructor() {
  companion object {
    @Volatile
    private var instance: DataManagerData? = null
    fun getInstance(login_token_path: String): DataManagerData {
      val checkInstance = instance
      if (checkInstance != null) {
        return checkInstance
      }

      return synchronized(this) {
        val checkInstanceAgain = instance
        if (checkInstanceAgain != null) {
          checkInstanceAgain
        } else {
          val created = DataManagerData()
          created.initialize(login_token_path)
          instance = created
          created
        }
      }
    }
  }

  val lock = Mutex()
  var loginToken: String? = null
  var keyValues = mutableMapOf<String, JSONObject>()
  var registeredFiles = mutableMapOf<String, JSONObject>()
  var connectedToServer = false

  // All UI visible State.
  var promptStateContainer: PromptState? = null

  internal val _serverStatus = MutableLiveData<Boolean>()
  val serverStatus: LiveData<Boolean> get() = _serverStatus

  internal val _promptState = MutableLiveData<PromptState>()
  val promptState: LiveData<PromptState> get() = _promptState


  fun initialize(login_token_path: String) {
    try {
      FileInputStream(login_token_path).use { stream ->
        loginToken = stream.readBytes().toString(Charsets.UTF_8)
      }
    } catch (e: FileNotFoundException) {
      // Log.i(TAG, "loginToken not found.")
    }
  }
}
