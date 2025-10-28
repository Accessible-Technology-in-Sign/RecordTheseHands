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

  private val _explainTextVisible = MutableStateFlow(false)
  val explainTextVisible: StateFlow<Boolean> = _explainTextVisible

  private val _isReadTimerActive = MutableStateFlow(false)
  val isReadTimerActive: StateFlow<Boolean> = _isReadTimerActive

  private val _readCountdownDuration = MutableStateFlow(0)
  val readCountdownDuration: StateFlow<Int> = _readCountdownDuration

  private val _isRecordingTimerActive = MutableStateFlow(false)
  val isRecordingTimerActive: StateFlow<Boolean> = _isRecordingTimerActive

  private val _isExplainTimerActive = MutableStateFlow(false)
  val isExplainTimerActive: StateFlow<Boolean> = _isExplainTimerActive

  private val _recordingCountdownDuration = MutableStateFlow(0)
  val recordingCountdownDuration: StateFlow<Int> = _recordingCountdownDuration

  private val _recordingTimerKey = MutableStateFlow(0)
  val recordingTimerKey: StateFlow<Int> = _recordingTimerKey

  private val _viewedPrompts = MutableStateFlow<Set<Int>>(emptySet())
  val viewedPrompts: StateFlow<Set<Int>> = _viewedPrompts

  fun markPromptAsViewed(promptIndex: Int) {
    _viewedPrompts.value = _viewedPrompts.value + promptIndex
  }

  fun restartRecordingCountdown() {
    _recordingTimerKey.value += 1
    _isRecordingTimerActive.value = true
  }

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

  fun showExplainText() {
    _explainTextVisible.value = true
  }

  fun hideExplainText() {
    _explainTextVisible.value = false
  }

  fun setButtonState(recordVisible: Boolean, restartVisible: Boolean) {
    _recordButtonVisible.value = recordVisible
    _restartButtonVisible.value = restartVisible
  }

  fun setReadTimerActive(isActive: Boolean) {
    _isReadTimerActive.value = isActive
  }

  fun setReadCountdownDuration(durationMs: Int) {
    _readCountdownDuration.value = durationMs
  }

  fun setRecordingTimerActive(isActive: Boolean) {
    _isRecordingTimerActive.value = isActive
  }

  fun setRecordingCountdownDuration(durationMs: Int) {
    _recordingCountdownDuration.value = durationMs
  }

  fun setExplainTimerActive(isActive: Boolean) {
    _isExplainTimerActive.value = isActive
  }
}
