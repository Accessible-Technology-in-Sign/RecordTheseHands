/**
 * This file is part of Record These Hands, licensed under the MIT license.
 *
 * Copyright (c) 2023-2024
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package edu.gatech.ccg.recordthesehands.upload

import android.content.Context
import android.util.Base64
import android.util.Log
import edu.gatech.ccg.recordthesehands.Constants.APP_VERSION
import edu.gatech.ccg.recordthesehands.fromHex
import edu.gatech.ccg.recordthesehands.toHex
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

class UploadSession(
  context: Context,
  private val registeredFile: RegisteredFile
) {
  private val dataManager = DataManager.getInstance(context)
  private val relativePath: String
    get() {
      return registeredFile.relativePath
    }

  private val loginToken: String
    get() {
      return dataManager.dataManagerData.loginToken!!
    }

  companion object {
    private val TAG = UploadSession::class.simpleName
  }

  private suspend fun acquireMd5(): Boolean {
    val filepath = File(dataManager.context.filesDir, relativePath)

    val digest = MessageDigest.getInstance("MD5")
    val STREAM_BUFFER_LENGTH = 1048576  // 1MiB
    val buffer = ByteArray(STREAM_BUFFER_LENGTH)
    Log.i(TAG, "computing md5sum for $filepath")
    try {
      FileInputStream(filepath.absolutePath).use { stream ->
        var read = stream.read(buffer, 0, STREAM_BUFFER_LENGTH)
        while (read > -1) {
          digest.update(buffer, 0, read)
          read = stream.read(buffer, 0, STREAM_BUFFER_LENGTH)
          if (UploadService.isPaused()) {
            throw InterruptedUploadException("Computation of md5sum was interrupted.")
          }
        }
      }
    } catch (e: FileNotFoundException) {
      Log.e(TAG, "File not found when computing md5sum: $filepath")
      return false
    }
    registeredFile.md5sum = toHex(digest.digest())
    Log.i(TAG, "Computed md5sum for \"$filepath\" as ${registeredFile.md5sum}")
    registeredFile.saveState()
    return true
  }

  private suspend fun acquireUploadLink(): Boolean {
    if (UploadService.isPaused()) {
      throw InterruptedUploadException("acquireUploadLink interrupted.")
    }
    Log.i(TAG, "acquiring upload link: \"${relativePath}\"")
    val url = URL(dataManager.getServer() + "/upload")
    val (code, data) =
      dataManager.serverFormPostRequest(
        url,
        mapOf(
          "app_version" to APP_VERSION,
          "login_token" to loginToken,
          "path" to relativePath,
          "md5" to registeredFile.md5sum!!,
          "file_size" to registeredFile.fileSize!!.toString(),
          "tutorial_mode" to registeredFile.tutorialMode.toString(),
        )
      )
    if (code >= 200 && code < 300) {
      if (data == null) {
        Log.e(TAG, "data was null.")
        return false
      }
      val json = JSONObject(data)
      registeredFile.uploadLink = json.getString("uploadLink")
      Log.i(TAG, "uploadLink: ${registeredFile.uploadLink}")
      registeredFile.saveState()
    } else {
      Log.e(
        TAG,
        "Unable to obtain upload link for \"${relativePath}\".  Will try again later."
      )
      return false
    }
    return true
  }

  private suspend fun acquireSessionLink(): Boolean {
    if (UploadService.isPaused()) {
      throw InterruptedUploadException("acquireSessionLink interrupted.")
    }
    Log.i(TAG, "Getting Upload link: \"${relativePath}\"")
    val url = URL(registeredFile.uploadLink)
    val md5Base64 =
      Base64.encode(fromHex(registeredFile.md5sum!!), Base64.NO_WRAP).toString(Charsets.UTF_8)
    val (code, _, outputFromHeader) =
      dataManager.serverRequest(
        url,
        "POST",
        mapOf(
          "Content-Length" to "0",
          "Content-Type" to "video/mp4",
          "Content-MD5" to md5Base64,
          "X-Goog-Resumable" to "start"
        ),
        ByteArray(0),
        "Location"
      )
    if (code >= 200 && code < 300) {
      check(outputFromHeader != null) { "outputFromHeader was null" }
      registeredFile.sessionLink = outputFromHeader
      Log.i(TAG, "sessionLink: ${registeredFile.sessionLink}")
      registeredFile.saveState()
    } else {
      Log.e(
        TAG,
        "Unable to obtain session link for \"${relativePath}\".  Will try again later."
      )
      return false
    }
    return true
  }

  private suspend fun acquireSessionState(): Long? {
    if (UploadService.isPaused()) {
      throw InterruptedUploadException("acquireSessionState interrupted.")
    }
    val numSavedBytes: Long

    Log.i(TAG, "acquireSessionState: \"${relativePath}\"")
    val url = URL(registeredFile.sessionLink)
    val (code, _, outputFromHeader) =
      dataManager.serverRequest(
        url,
        "PUT",
        mapOf(
          "Content-Length" to "0",
          "Content-Range" to "bytes */" + registeredFile.fileSize,
        ),
        ByteArray(0),
        "Range"
      )
    if (code >= 200 && code < 300) {
      registeredFile.uploadCompleted = true
      Log.i(TAG, "uploadCompleted")
      registeredFile.saveState()
      return registeredFile.fileSize
    } else if (code == 308) {  // Continue uploading.
      if (outputFromHeader == null) {
        numSavedBytes = 0L
        Log.i(TAG, "No data has been uploaded.")
      } else {
        Log.i(TAG, "Upload has not yet been completed.  Range: $outputFromHeader")
        val m = Regex("^bytes=(\\d+)-(\\d+)$").matchEntire(outputFromHeader)
        check(m != null) { "Range header field did not have proper format." }
        val firstSavedByte = m.groups[1]!!.value.toLong()
        check(firstSavedByte == 0L) { "The Range header did not start from 0" }
        numSavedBytes = m.groups[2]!!.value.toLong() + 1L
        Log.i(TAG, "$numSavedBytes bytes have already been uploaded.")
      }
    } else {
      Log.e(
        TAG,
        "Session link for \"${relativePath}\" is broken, starting upload from scratch."
      )
      registeredFile.resetState()
      return null
    }
    return numSavedBytes
  }

  private suspend fun uploadFileToSession(numSavedBytes: Long): Boolean {
    if (UploadService.isPaused()) {
      throw InterruptedUploadException("uploadFileToSession interrupted.")
    }
    Log.i(TAG, "uploading from byte $numSavedBytes")

    val filepath = File(dataManager.context.filesDir, relativePath)
    val url = URL(registeredFile.sessionLink)

    val urlConnection = url.openConnection() as HttpsURLConnection
    dataManager.setAppropriateTrust(urlConnection)

    var code: Int = -1
    var output: String? = null
    var interrupted = false
    try {
      urlConnection.setDoOutput(true)
      urlConnection.setFixedLengthStreamingMode(registeredFile.fileSize!! - numSavedBytes)
      urlConnection.requestMethod = "PUT"
      urlConnection.setRequestProperty(
        "Content-Length",
        "${registeredFile.fileSize!! - numSavedBytes}"
      )
      urlConnection.setRequestProperty(
        "Content-Range",
        "bytes $numSavedBytes-${registeredFile.fileSize!! - 1L}/${registeredFile.fileSize!!}"
      )

      urlConnection.outputStream.use { outputStream ->
        FileInputStream(filepath.absolutePath).use { fileStream ->
          val STREAM_BUFFER_LENGTH = 1048576  // 1MiB
          val buffer = ByteArray(STREAM_BUFFER_LENGTH)
          Log.i(TAG, "Uploading $filepath")
          fileStream.skip(numSavedBytes)
          var read = fileStream.read(buffer, 0, STREAM_BUFFER_LENGTH)
          while (read > -1) {
            outputStream.write(buffer, 0, read)
            if (UploadService.isPaused()) {
              throw InterruptedUploadException("Uploading of file interrupted.")
            }
            read = fileStream.read(buffer, 0, STREAM_BUFFER_LENGTH)
          }
        }
      }

      code = urlConnection.responseCode
      dataManager.getDataStream(urlConnection).use { inputStream ->
        output = inputStream.readBytes().toString(Charsets.UTF_8)
      }
      if (code < 200 || code >= 300) {
        registeredFile.uploadCompleted = true
        registeredFile.saveState()
        return true
      } else if (urlConnection.responseCode >= 400) {
        Log.e(TAG, "Response code: $code " + output)
        registeredFile.resetState()
        return false
      }
    } catch (e: InterruptedUploadException) {
      Log.i(TAG, "InterruptedUploadException caught and being rethrown: ${e.message}")
      interrupted = true
      throw e
    } catch (e: IOException) {
      Log.e(TAG, "Upload Failed: $e")
    } finally {
      if (!interrupted) {
        dataManager.dataManagerData._serverStatus.postValue(code >= 200 && code < 400)
      }
      urlConnection.disconnect()
    }
    return true
  }

  private suspend fun verifyUpload(): Boolean {
    if (UploadService.isPaused()) {
      throw InterruptedUploadException("verifyUpload interrupted.")
    }
    Log.i(TAG, "verifying upload: \"${relativePath}\"")
    val url = URL(dataManager.getServer() + "/verify")
    val (code, data) =
      dataManager.serverFormPostRequest(
        url,
        mapOf(
          "app_version" to APP_VERSION,
          "login_token" to loginToken,
          "path" to relativePath,
          "md5" to registeredFile.md5sum!!,
          "file_size" to registeredFile.fileSize!!.toString(),
          "tutorial_mode" to registeredFile.tutorialMode.toString(),
        )
      )
    if (code >= 200 && code < 300) {
      if (data == null) {
        Log.e(TAG, "data was null.")
        return false
      }
      val json = JSONObject(data)
      registeredFile.uploadVerified = json.getBoolean("verified")
      Log.i(TAG, "uploadVerified: ${registeredFile.uploadVerified}")
      registeredFile.saveState()
      deleteLocalFile()
    } else if (code == 503) {
      if (data == null) {
        return false
      }
      val json = JSONObject(data)
      if (json.has("fileNotFound") && json.getBoolean("fileNotFound")) {
        Log.w(TAG, "verify said file not found, getting state again.")
        registeredFile.uploadCompleted = false
        registeredFile.uploadVerified = false
        registeredFile.saveState()
      }
      return false
    } else {
      Log.e(
        TAG,
        "Unable to verify \"${relativePath}\".  Will try again later."
      )
      return true  // Continue with other files.
    }
    return true
  }

  private suspend fun deleteLocalFile() {
    val filepath = File(dataManager.context.filesDir, relativePath)
    registeredFile.deleteState()
    if (filepath.delete()) {
      Log.i(TAG, "Deleted $filepath")
    } else {
      Log.e(TAG, "failed to delete $filepath")
    }
  }

  suspend fun tryUploadFile(): Boolean {
    if (UploadService.isPaused()) {
      throw InterruptedUploadException("tryUploadFile interrupted.")
    }

    if (registeredFile.md5sum != null && !Regex("^[a-f0-9]{32}$").matches(registeredFile.md5sum!!)) {
      Log.e(TAG, "An error has occurred, md5sum is not valid.")
      return false
    }

    if (registeredFile.uploadCompleted) {
      Log.i(TAG, "Upload has already been completed.")
      if (!registeredFile.uploadVerified) {
        if (!verifyUpload()) {
          return false
        }
      }
      return true
    }

    val filepath = File(dataManager.context.filesDir, relativePath)
    if (!filepath.exists()) {
      Log.e(TAG, "File $filepath is registered but does not exist.")
      return true  // Continue with other files.
    }
    if (registeredFile.fileSize == null) {
      val size = filepath.length()
      check(size != 0L) { "Could not determine the file size." }
      registeredFile.fileSize = size
    } else {
      if (filepath.length() != registeredFile.fileSize) {
        Log.e(TAG, "fileSize has changed resetting State.")
        registeredFile.resetState()
        return true  // continue with other files.
      }
    }

    // Step 1 is computing an md5.
    if (registeredFile.md5sum == null) {
      if (!acquireMd5()) {
        return false
      }
    }
    // Step 2 is to get the upload link.
    if (registeredFile.uploadLink == null) {
      if (!acquireUploadLink()) {
        return false
      }
    }
    // Step 3 is to obtain the sessionLink.
    var createdNewSessionLink = false
    if (registeredFile.sessionLink == null) {
      if (!acquireSessionLink()) {
        return false
      }
      createdNewSessionLink = true
    }
    // Step 4 is to get the state of the upload.
    var numSavedBytes = 0L
    if (!createdNewSessionLink) {
      val sessionState = acquireSessionState()
      if (sessionState == null) {
        return false
      }
      numSavedBytes = sessionState
    }
    // Step 5 is to upload the file.
    if (!registeredFile.uploadCompleted) {
      if (!uploadFileToSession(numSavedBytes)) {
        return false
      }
    }
    // Step 6 is to verify with the server that the file is properly uploaded.
    // Note, the md5 has already been verified by the gcs bucket's resumable upload.
    if (!registeredFile.uploadVerified) {
      if (!verifyUpload()) {
        return false
      }
    }
    return true
  }
}
