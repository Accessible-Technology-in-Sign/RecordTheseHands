package edu.gatech.ccg.recordthesehands.upload

enum class UploadStatus {
  IDLE,
  UPLOADING,
  SUCCESS,
  FAILED,
  INTERRUPTED
}

data class UploadState(
  val status: UploadStatus = UploadStatus.IDLE,
  val progress: Int = 0
)
