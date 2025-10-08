package edu.gatech.ccg.recordthesehands.upload

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_RESUME_ON_STARTUP_TIMEOUT

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
      Log.d("BootReceiver", "Boot completed, scheduling upload work.")
      UploadPauseManager.pauseUploadTimeout(UPLOAD_RESUME_ON_STARTUP_TIMEOUT)
      UploadWorkManager.scheduleInitialUpload(context)
      UploadWorkManager.schedulePeriodicWatchdog(context)
    }
  }
}
