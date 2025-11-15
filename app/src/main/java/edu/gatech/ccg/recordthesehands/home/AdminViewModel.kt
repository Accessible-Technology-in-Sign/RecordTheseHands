package edu.gatech.ccg.recordthesehands.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.gatech.ccg.recordthesehands.upload.DataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class AdminViewModel : ViewModel() {
  var newDeviceId by mutableStateOf("")
  var newUsername by mutableStateOf("")
  var adminPassword by mutableStateOf("")
  var isAttaching by mutableStateOf(false)

  private val _attachResultFlow = MutableSharedFlow<Pair<Boolean, String?>>()
  val attachResultFlow = _attachResultFlow.asSharedFlow()

  fun attachToAccount(dataManager: DataManager) {
    viewModelScope.launch(Dispatchers.IO) {
      isAttaching = true
      val result = dataManager.attachToAccount(newUsername, adminPassword)
      dataManager.setCheckVersion(false)
      dataManager.checkServerConnection()
      _attachResultFlow.emit(result)
      isAttaching = false
    }
  }
}
