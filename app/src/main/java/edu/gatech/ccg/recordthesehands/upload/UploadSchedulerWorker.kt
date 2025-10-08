package edu.gatech.ccg.recordthesehands.upload

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture

class UploadSchedulerWorker(appContext: Context, workerParams: WorkerParameters) :
  CoroutineWorker(appContext, workerParams) {

  companion object {
    val TAG: String = UploadSchedulerWorker::class.java.simpleName
  }

  override suspend fun doWork(): Result {
    Log.d(TAG, "Watchdog worker running.")
    val workManager = WorkManager.getInstance(applicationContext)
    val future: ListenableFuture<List<WorkInfo>> =
      workManager.getWorkInfosForUniqueWork(UploadWorker.WORK_NAME)
    val workInfos = future.get()

    if (workInfos == null || workInfos.isEmpty()) {
      Log.i(TAG, "No scheduled upload work found, starting a new chain.")
      UploadWorkManager.scheduleInitialUpload(applicationContext)
    } else {
      Log.d(TAG, "Upload work is already scheduled. Watchdog is happy.")
    }

    return Result.success()
  }
}
