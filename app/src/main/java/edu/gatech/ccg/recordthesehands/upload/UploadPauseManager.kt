package edu.gatech.ccg.recordthesehands.upload

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.time.Instant

object UploadPauseManager {
  private val TAG = UploadPauseManager::class.simpleName
  private const val PREFS_NAME = "UploadPausePrefs"
  private const val KEY_PAUSE_UNTIL = "pauseUntil"

  private lateinit var prefs: SharedPreferences

  fun init(context: Context) {
    prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  }

  private var pauseUntil: Instant?
    get() {
      val timestamp = prefs.getLong(KEY_PAUSE_UNTIL, -1)
      return if (timestamp != -1L) Instant.ofEpochMilli(timestamp) else null
    }
    set(value) {
      if (value == null) {
        prefs.edit().remove(KEY_PAUSE_UNTIL).apply()
      } else {
        prefs.edit().putLong(KEY_PAUSE_UNTIL, value.toEpochMilli()).apply()
      }
    }

  fun pauseUploadTimeoutAtLeast(millis: Long) {
    pauseUploadUntilAtLeast(Instant.now().plusMillis(millis))
  }

  fun pauseUploadTimeout(millis: Long) {
    pauseUploadUntil(Instant.now().plusMillis(millis))
  }

  fun pauseUploadUntilAtLeast(timestamp: Instant?) {
    pauseUntil?.let {
      if (it > timestamp) {
        Log.d(TAG, "Upload already paused past \"at least\" timestamp.")
        return
      }
    }
    pauseUntil = timestamp
    Log.d(TAG, "Paused upload until ${pauseUntil}")
  }

  fun pauseUploadUntil(timestamp: Instant?) {
    pauseUntil = timestamp
    Log.d(TAG, "Paused upload until ${pauseUntil}")
  }

  fun isPaused(): Boolean {
    val tmpPauseUntil = pauseUntil ?: return false
    return tmpPauseUntil.isAfter(Instant.now())
  }
}
