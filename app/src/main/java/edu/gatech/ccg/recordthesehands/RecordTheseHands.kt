package edu.gatech.ccg.recordthesehands

import android.app.Application
import android.content.Context

class RecordTheseHands : Application() {

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
