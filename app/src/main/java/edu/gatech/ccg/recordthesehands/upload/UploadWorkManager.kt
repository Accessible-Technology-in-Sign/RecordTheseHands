package edu.gatech.ccg.recordthesehands.upload

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import edu.gatech.ccg.recordthesehands.Constants
import edu.gatech.ccg.recordthesehands.toConsistentString
import edu.gatech.ccg.recordthesehands.upload.UploadWorker.Companion.TAG
import edu.gatech.ccg.recordthesehands.upload.UploadWorker.Companion.WORK_NAME
import java.time.Instant
import java.util.concurrent.TimeUnit

object UploadWorkManager {

  private val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

  fun schedulePeriodicWatchdog(context: Context) {
    val watchdogRequest =
      PeriodicWorkRequestBuilder<UploadSchedulerWorker>(15, TimeUnit.MINUTES)
        .setConstraints(constraints)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
      "UploadScheduler",
      ExistingPeriodicWorkPolicy.KEEP,
      watchdogRequest
    )
  }

  fun scheduleNextPeriodic(context: Context) {
    val nextRequest = OneTimeWorkRequestBuilder<UploadWorker>()
      .setInitialDelay(Constants.UPLOAD_PERIODIC_TIMEOUT, TimeUnit.MILLISECONDS)
      .setConstraints(constraints)
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
      WORK_NAME,
      ExistingWorkPolicy.REPLACE,
      nextRequest
    )
    val nextTime = Instant.now().plusMillis(Constants.UPLOAD_PERIODIC_TIMEOUT)
    Log.d(TAG, "Scheduled next UploadWorker for ${nextTime.toConsistentString()}.")
  }

  fun scheduleInitialUpload(context: Context) {
    val uploadWorkRequest = OneTimeWorkRequestBuilder<UploadWorker>()
      .setConstraints(constraints)
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
      UploadWorker.WORK_NAME,
      ExistingWorkPolicy.KEEP,
      uploadWorkRequest
    )
  }

  fun scheduleUpload(context: Context, delay: Long, unit: TimeUnit) {
    val uploadWorkRequest = OneTimeWorkRequestBuilder<UploadWorker>()
      .setInitialDelay(delay, unit)
      .setConstraints(constraints)
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
      UploadWorker.WORK_NAME,
      ExistingWorkPolicy.REPLACE,
      uploadWorkRequest
    )
  }
}
