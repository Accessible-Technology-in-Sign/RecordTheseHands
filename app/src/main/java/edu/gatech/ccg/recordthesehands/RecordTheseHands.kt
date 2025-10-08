package edu.gatech.ccg.recordthesehands

import android.app.Application
import android.content.Context
import edu.gatech.ccg.recordthesehands.upload.UploadPauseManager
import edu.gatech.ccg.recordthesehands.upload.UploadWorkManager

class RecordTheseHands : Application() {

  override fun onCreate() {
    super.onCreate()
    UploadPauseManager.init(this)
    UploadWorkManager.scheduleInitialUpload(this)
    UploadWorkManager.schedulePeriodicWatchdog(this)
  }

  init {
    instance = this
  }

  companion object {
    private var instance: RecordTheseHands? = null

    fun applicationContext(): Context {
      return instance!!.applicationContext
    }
  }
}
