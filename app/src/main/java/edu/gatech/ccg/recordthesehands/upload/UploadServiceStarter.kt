/**
 * This file is part of Record These Hands, licensed under the MIT license.
 *
 * Copyright (c) 2023 Google
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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class UploadServiceStarter : BroadcastReceiver() {

  companion object {
    private val TAG = UploadServiceStarter::class.simpleName
  }

  override fun onReceive(context: Context, intent: Intent) {
    var action = intent.getAction();
    if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
      Log.d(TAG, "System Boot Completed, starting upload service.")
    } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
      Log.d(TAG, "Package Replaced, starting upload service.")
    } else {
      Log.e(TAG, "Unknown Intent triggered, starting upload service anyway.")
    }
    val intent = Intent(context, UploadService::class.java)
    context.startForegroundService(intent)
  }
}
