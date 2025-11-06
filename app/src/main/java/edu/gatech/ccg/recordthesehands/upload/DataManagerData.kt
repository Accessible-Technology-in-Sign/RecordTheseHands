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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

object DataManagerData {
  val initializationStarted = AtomicBoolean(false)
  val initializationLatch = CountDownLatch(1)

  val lock = Mutex()
  var loginToken: String? = null
  var keyValues = mutableMapOf<String, JSONObject>()

  // All UI visible State.
  var promptStateContainer: PromptState? = null

  internal val _serverStatus = MutableLiveData<ServerState>()
  val serverStatus: LiveData<ServerState> get() = _serverStatus

  internal val _promptState = MutableLiveData<PromptState>()
  val promptState: LiveData<PromptState> get() = _promptState

  internal val _uploadState = MutableLiveData<UploadState>()
  val uploadState: LiveData<UploadState> get() = _uploadState
}