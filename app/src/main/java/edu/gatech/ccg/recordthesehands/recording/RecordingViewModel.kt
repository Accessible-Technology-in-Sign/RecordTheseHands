package edu.gatech.ccg.recordthesehands.recording

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class RecordingViewModel : ViewModel() {
  private val _timerText = MutableStateFlow("00:00")
  val timerText: StateFlow<String> = _timerText

  private val _recordButtonVisible = MutableStateFlow(true)
  val recordButtonVisible: StateFlow<Boolean> = _recordButtonVisible

  private val _restartButtonVisible = MutableStateFlow(false)
  val restartButtonVisible: StateFlow<Boolean> = _restartButtonVisible

  private val _isRecording = MutableStateFlow(false)
  val isRecording: StateFlow<Boolean> = _isRecording

  private val _goTextVisible = MutableStateFlow(false)
  val goTextVisible: StateFlow<Boolean> = _goTextVisible

  fun onTick(newTime: String) {
    _timerText.value = newTime
  }

  fun setRecordingState(isRecording: Boolean) {
    _isRecording.value = isRecording
  }

  fun showGoText() {
    _goTextVisible.value = true
  }

  fun hideGoText() {
    _goTextVisible.value = false
  }

  fun setButtonState(recordVisible: Boolean, restartVisible: Boolean) {
    _recordButtonVisible.value = recordVisible
    _restartButtonVisible.value = restartVisible
  }
}
