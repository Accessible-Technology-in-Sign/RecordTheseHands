/**
 * This file is part of Record These Hands, licensed under the MIT license.
 *
 * Copyright (c) 2023-2024
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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_LOOP_TIMEOUT
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_NOTIFICATION_CHANNEL_ID
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_NOTIFICATION_ID
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_RESUME_ON_START_TIMEOUT
import edu.gatech.ccg.recordthesehands.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import java.util.Date

/**
 * The upload service.
 */
class UploadService : LifecycleService() {

  companion object {
    private val TAG = UploadService::class.simpleName

    private var pauseUntil: Date? = null

    fun pauseUploadTimeout(millis: Long) {
      pauseUploadUntil(Date(Calendar.getInstance().timeInMillis + millis))
    }

    fun pauseUploadUntil(timestamp: Date?) {
      pauseUntil = timestamp
      Log.d(TAG, "Paused UploadService until ${pauseUntil}")
    }

    fun isPaused(): Boolean {
      val tmpPauseUntil = pauseUntil ?: return false
      return tmpPauseUntil.after(Calendar.getInstance().time)
    }

  }

  private var notificationManager: NotificationManager? = null

  private var notifiedOfCompletion = false


  /**
   * onCreate.
   */
  override fun onCreate() {
    super.onCreate()
    val channel = NotificationChannel(
      UPLOAD_NOTIFICATION_CHANNEL_ID, "Upload Service", NotificationManager.IMPORTANCE_LOW
    )
    channel.description = "Uploads Sign Language Videos."
    notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    notificationManager!!.createNotificationChannel(channel)

    lifecycleScope.launch(Dispatchers.IO) {
      val dataManager = DataManager(applicationContext)
      try {
        dataManager.dataManagerData.lock.withLock {
          dataManager.runDirectives()
        }
      } catch (e: InterruptedUploadException) {
        Log.w(TAG, "Paused, skipping directives.")
      }
      pauseUploadTimeout(UPLOAD_RESUME_ON_START_TIMEOUT)
      while (true) {
        if (!isPaused()) {
          Log.i(TAG, "Upload loop is running.")
          try {
            if (dataManager.uploadData(notificationManager!!) { progress ->
                Log.d(TAG, "Upload progress: $progress%")
              }) {
              if (!notifiedOfCompletion) {
                notificationManager!!.notify(
                  UPLOAD_NOTIFICATION_ID,
                  createNotification("Uploading complete", "")
                )
                notifiedOfCompletion = true
              }
            } else {
              notifiedOfCompletion = false
            }
          } catch (e: InterruptedUploadException) {
            Log.w(TAG, "data upload interrupted: ${e.message}")
          }
        } else {
          Log.i(TAG, "Upload loop is paused.")
        }
        delay(UPLOAD_LOOP_TIMEOUT)
      }
    }
  }

  /**
   * onBind is not allowed for this service.
   */
  override fun onBind(intent: Intent): IBinder? {
    super.onBind(intent)
    return null
  }

  fun createNotification(title: String, message: String): Notification {
    return Notification.Builder(this, UPLOAD_NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(R.drawable.upload_service_notification_icon)
      .setContentTitle(title)
      .setContentText(message)
      .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
      .setTicker("$title: $message")
      .build()
  }

  /**
   * onStartCommand starts the service, which keeps a thread running continuously.
   */
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.i(TAG, "onStartCommand")
    super.onStartCommand(intent, flags, startId)
    startForeground(UPLOAD_NOTIFICATION_ID, createNotification("Upload Service started", ""))
    return START_STICKY
  }
}
