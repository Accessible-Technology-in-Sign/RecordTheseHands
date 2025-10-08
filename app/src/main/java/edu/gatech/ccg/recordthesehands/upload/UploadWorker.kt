package edu.gatech.ccg.recordthesehands.upload

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class UploadWorker(val appContext: Context, workerParams: WorkerParameters) :
  CoroutineWorker(appContext, workerParams) {

  companion object {
    val TAG: String = UploadWorker::class.java.simpleName
    const val WORK_NAME = "UploadWorker"
  }

  override suspend fun doWork(): Result {
    try {
      if (UploadPauseManager.isPaused()) {
        Log.i(TAG, "UploadWorker is paused. Skipping work.")
        return Result.success()
      }

      Log.d(TAG, "UploadWorker started.")
      val dataManager = DataManager.getInstance(applicationContext)

      try {
        dataManager.uploadData()
      } catch (e: InterruptedUploadException) {
        Log.w(TAG, "Upload interrupted, will retry later.", e)
        return Result.success() // Not a failure, just paused
      } catch (e: Exception) {
        Log.e(TAG, "Upload failed.", e)
        return Result.failure()
      }

      Log.d(TAG, "UploadWorker finished successfully.")
      return Result.success()
    } finally {
      UploadWorkManager.scheduleNextPeriodic(appContext)
    }
  }
}
