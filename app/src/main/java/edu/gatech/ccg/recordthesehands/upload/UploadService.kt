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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import edu.gatech.ccg.recordthesehands.*

class UploadFileRunnable(var filepath: String) : Runnable {

  companion object {
    private val TAG = UploadFileRunnable::class.simpleName
  }

  override fun run() {
    Log.i(TAG, "Thread to upload $filepath.");
  }
}

/**
 * The upload service.
 */
class UploadService : Service() {

  private var NOTIFICATION_ID = 1
  private var NOTIFICATION_CHANNEL_ID = "upload_service"

  /**
   * onCreate.
   */
  override fun onCreate() {
    val channel = NotificationChannel(
      NOTIFICATION_CHANNEL_ID, "Upload Service", NotificationManager.IMPORTANCE_LOW
    )
    channel.description = "Uploads Sign Language Videos.";
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannel(channel)
  }

  /**
   * onBind is not allowed for this service.
   */
  override fun onBind(intent: Intent): IBinder? {
    return null
  }

  /**
   * onStartCommand starts the service, which keeps a thread running continuously.
   */
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val notification: Notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(R.drawable.upload_service_notification_icon)
      .setContentTitle("Data will upload.")
      .setContentText("TODO, provide a status here.")
      .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
      .setTicker("ticker text")
      .build()
    startForeground(NOTIFICATION_ID, notification)

    var thread = Thread(UploadFileRunnable("/fake/file/path"))
    thread.start()

    return START_STICKY
  }

}
