package edu.gatech.ccg.recordthesehands.upload

enum class ServerStatus {
  UNKNOWN,
  NO_INTERNET,
  NO_SERVER,
  SERVER_ERROR,
  NO_LOGIN,
  ACTIVE
}

data class ServerState(
  val status: ServerStatus = ServerStatus.UNKNOWN,
)
