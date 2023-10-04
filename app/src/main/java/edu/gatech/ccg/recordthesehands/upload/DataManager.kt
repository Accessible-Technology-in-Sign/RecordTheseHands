/**
 * This file is part of Record These Hands, licensed under the MIT license.
 *
 * Copyright (c) 2023 Google
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

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import edu.gatech.ccg.recordthesehands.R
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dataStore")
private val Context.registerFileStore: DataStore<Preferences> by preferencesDataStore(
  name = "registerFileStore"
)

class InterruptedUploadException(message: String) : InterruptedIOException(message)

fun toHex(data: ByteArray): String {
  return data.joinToString(separator = "") { x -> "%02x".format(x) }
}

fun fromHex(hex: String): ByteArray {
  check(hex.length % 2 == 0) { "Must have an even length" }

  val byteIterator = hex.chunkedSequence(2)
    .map { it.toInt(16).toByte() }
    .iterator()

  return ByteArray(hex.length / 2) { byteIterator.next() }
}

fun makeToken(username: String, password: String): String {
  val digest = MessageDigest.getInstance("SHA-256")
  var token = "$username:$password"
  for (i in 0..999) {
    token = "$username:" + toHex(digest.digest(token.toByteArray(Charsets.UTF_8)))
  }
  return token
}

class UploadSession(
  val context: Context, val loginToken: String,
  val relativePath: String
) {

  companion object {
    private val TAG = UploadSession::class.simpleName
  }

  var fileSize: Long? = null
  var md5sum: String? = null
  var uploadLink: String? = null
  var sessionLink: String? = null
  var uploadCompleted: Boolean = false
  var uploadVerified: Boolean = false

  fun loadState(registerStoreValue: String) {
    Log.i(TAG, "Loading current session state $registerStoreValue")
    fileSize = null
    md5sum = null
    uploadLink = null
    sessionLink = null
    uploadCompleted = false
    uploadVerified = false

    val inputJson = JSONObject(registerStoreValue)
    if (inputJson.has("fileSize")) {
      fileSize = inputJson.getLong("fileSize")
    }
    if (inputJson.has("md5")) {
      md5sum = inputJson.getString("md5")
    }
    if (inputJson.has("uploadLink")) {
      uploadLink = inputJson.getString("uploadLink")
    }
    if (inputJson.has("sessionLink")) {
      sessionLink = inputJson.getString("sessionLink")
    }
    if (inputJson.has("uploadCompleted")) {
      uploadCompleted = inputJson.getBoolean("uploadCompleted")
    }
    if (inputJson.has("uploadVerified")) {
      uploadVerified = inputJson.getBoolean("uploadVerified")
    }
  }

  suspend fun saveState() {
    Log.i(TAG, "Saving current session state")
    val keyObject = stringPreferencesKey(relativePath)
    context.registerFileStore.edit { preferences ->
      val json = JSONObject()
      if (md5sum != null) {
        json.put("md5", md5sum)
      }
      if (fileSize != null) {
        json.put("fileSize", fileSize)
      }
      if (uploadCompleted) {
        json.put("uploadCompleted", uploadCompleted)
      }
      if (uploadVerified) {
        json.put("uploadVerified", uploadVerified)
      }
      if (uploadLink != null) {
        json.put("uploadLink", uploadLink)
      }
      if (sessionLink != null) {
        json.put("sessionLink", sessionLink)
      }
      preferences[keyObject] = json.toString()
    }
  }

  suspend fun resetState() {
    fileSize = null
    md5sum = null
    uploadLink = null
    sessionLink = null
    uploadCompleted = false
    uploadVerified = false

    val keyObject = stringPreferencesKey(relativePath)
    context.registerFileStore.edit { preferences ->
      preferences[keyObject] = JSONObject().toString()
    }
  }

  suspend fun deleteState() {
    fileSize = null
    md5sum = null
    uploadLink = null
    sessionLink = null
    uploadCompleted = false
    uploadVerified = false

    val keyObject = stringPreferencesKey(relativePath)
    context.registerFileStore.edit { preferences ->
      preferences.remove(keyObject)
    }
  }

  private suspend fun acquireMd5(): Boolean {
    val filepath = File(context.filesDir, relativePath)

    val digest = MessageDigest.getInstance("MD5")
    val STREAM_BUFFER_LENGTH = 1048576  // 1MiB
    val buffer = ByteArray(STREAM_BUFFER_LENGTH)
    Log.i(TAG, "computing md5sum for $filepath")
    val stream = FileInputStream(filepath.absolutePath)
    var read = stream.read(buffer, 0, STREAM_BUFFER_LENGTH)
    while (read > -1) {
      digest.update(buffer, 0, read)
      read = stream.read(buffer, 0, STREAM_BUFFER_LENGTH)
      if (UploadService.isPaused()) {
        throw InterruptedUploadException("Computation of md5sum was interrupted.")
      }
    }
    md5sum = toHex(digest.digest())
    Log.i(TAG, "Computed md5sum for \"$filepath\" as $md5sum")
    saveState()
    return true
  }

  private suspend fun acquireUploadLink(): Boolean {
    if (UploadService.isPaused()) {
      throw InterruptedUploadException("acquireUploadLink interrupted.")
    }
    Log.i(TAG, "acquiring upload link: \"${relativePath}\"")
    val url = URL(DataManager.SERVER + "/upload")
    val (code, data) =
      DataManager.serverFormPostRequest(
        url,
        mapOf(
          "login_token" to loginToken!!,
          "path" to relativePath,
          "md5" to md5sum!!,
          "file_size" to fileSize!!.toString(),
        )
      )
    if (code >= 200 && code < 300) {
      if (data == null) {
        Log.e(TAG, "data was null.")
        return false
      }
      val json = JSONObject(data)
      uploadLink = json.getString("uploadLink")
      Log.i(TAG, "uploadLink: $uploadLink")
      saveState()
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
    val url = URL(uploadLink)
    val md5Base64 = Base64.encode(fromHex(md5sum!!), Base64.NO_WRAP).toString(Charsets.UTF_8)
    val (code, unused, outputFromHeader) =
      DataManager.serverRequest(
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
      sessionLink = outputFromHeader
      Log.i(TAG, "sessionLink: $sessionLink")
      saveState()
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

    Log.i(TAG, ": \"${relativePath}\"")
    val url = URL(sessionLink)
    val (code, unused, outputFromHeader) =
      DataManager.serverRequest(
        url,
        "PUT",
        mapOf(
          "Content-Length" to "0",
          "Content-Range" to "bytes */" + fileSize,
        ),
        ByteArray(0),
        "Range"
      )
    if (code >= 200 && code < 300) {
      uploadCompleted = true
      Log.i(TAG, "uploadCompleted")
      saveState()
      return fileSize
    } else if (code == 308) {  // Continue uploading.
      if (outputFromHeader == null) {
        numSavedBytes = 0L
        Log.i(TAG, "No data has been uploaded.")
      } else {
        Log.i(TAG, "Upload has not yet been completed.  Range: $outputFromHeader")
        check(outputFromHeader != null) { "outputFromHeader was null, no range field found" }
        val m = Regex("^bytes=(\\d+)-(\\d+)$").matchEntire(outputFromHeader!!)
        check(m != null) { "Range header field did not have proper format." }
        val firstSavedByte = m!!.groups[1]!!.value.toLong()
        check(firstSavedByte == 0L) { "The Range header did not start from 0" }
        numSavedBytes = m!!.groups[2]!!.value.toLong() + 1L
        Log.i(TAG, "$numSavedBytes bytes have already been uploaded.")
      }
    } else {
      Log.e(
        TAG,
        "Session link for \"${relativePath}\" is broken, starting upload from scratch."
      )
      resetState()
      return null
    }
    return numSavedBytes
  }

  private suspend fun uploadFileToSession(numSavedBytes: Long): Boolean {
    if (UploadService.isPaused()) {
      throw InterruptedUploadException("uploadFileToSession interrupted.")
    }
    Log.i(TAG, "uploading from byte $numSavedBytes")

    val filepath = File(context.filesDir, relativePath)
    val url = URL(sessionLink)

    val urlConnection = url.openConnection() as HttpsURLConnection
    if (DataManager.TRUST_ALL_CERTIFICATES) {
      DataManager.setTrustAllCertificates(urlConnection)
    }

    var code: Int = -1
    var output: String? = null
    try {
      urlConnection.setDoOutput(true)
      urlConnection.setFixedLengthStreamingMode(fileSize!! - numSavedBytes)
      urlConnection.setRequestMethod("PUT")
      urlConnection.setRequestProperty("Content-Length", "${fileSize!! - numSavedBytes}")
      urlConnection.setRequestProperty(
        "Content-Range",
        "bytes $numSavedBytes-${fileSize!! - 1L}/${fileSize!!}"
      )

      val outputStream = urlConnection.outputStream

      try {
        val STREAM_BUFFER_LENGTH = 1048576  // 1MiB
        val buffer = ByteArray(STREAM_BUFFER_LENGTH)
        Log.i(TAG, "Uploading Chunk of $filepath")
        val fileStream = FileInputStream(filepath.absolutePath)
        fileStream.skip(numSavedBytes)
        var read = fileStream.read(buffer, 0, STREAM_BUFFER_LENGTH)
        while (read > -1) {
          outputStream.write(buffer, 0, read)
          if (UploadService.isPaused()) {
            throw InterruptedUploadException("Uploading of file interrupted.")
          }
          read = fileStream.read(buffer, 0, STREAM_BUFFER_LENGTH)
        }
      } finally {
        outputStream.close()
      }

      urlConnection.headerFields.forEach {
        Log.i(TAG, "header ${it.key} -> ${it.value}")
      }

      code = urlConnection.responseCode
      val inputStream = DataManager.getDataStream(urlConnection)
      output = inputStream.readBytes().toString(Charsets.UTF_8)
      inputStream.close()
      if (code < 200 || code >= 300) {
        uploadCompleted = true
        saveState()
        return true
      }
      if (urlConnection.responseCode >= 400) {
        Log.e(TAG, "Response code: $code " + output)
        resetState()
        return false
      }
    } catch (e: InterruptedUploadException) {
      Log.i(TAG, "InterruptedUploadException caught and being rethrown: ${e.message}")
      throw e
    } catch (e: IOException) {
      Log.e(TAG, "Upload Failed: $e")
    } finally {
      urlConnection.disconnect()
    }
    return false
  }

  private suspend fun verifyUpload(): Boolean {
    if (UploadService.isPaused()) {
      throw InterruptedUploadException("verifyUpload interrupted.")
    }
    Log.i(TAG, "verifying upload: \"${relativePath}\"")
    val url = URL(DataManager.SERVER + "/verify")
    val (code, data) =
      DataManager.serverFormPostRequest(
        url,
        mapOf(
          "login_token" to loginToken!!,
          "path" to relativePath,
          "md5" to md5sum!!,
          "file_size" to fileSize!!.toString(),
        )
      )
    if (code >= 200 && code < 300) {
      if (data == null) {
        Log.e(TAG, "data was null.")
        return false
      }
      val json = JSONObject(data)
      uploadVerified = json.getBoolean("verified")
      Log.i(TAG, "uploadVerified: $uploadVerified")
      saveState()
      deleteLocalFile()
    } else if (code == 503) {
      if (data == null) {
        return false
      }
      val json = JSONObject(data)
      if (json.has("fileNotFound") && json.getBoolean("fileNotFound")) {
        Log.w(TAG, "verify said file not found, getting state again.")
        uploadCompleted = false
        uploadVerified = false
        saveState()
      }
      return false
    } else {
      Log.e(
        TAG,
        "Unable to verify \"${relativePath}\".  Will try again later."
      )
      return false
    }
    return true
  }

  private suspend fun deleteLocalFile() {
    val filepath = File(context.filesDir, relativePath)
    deleteState()
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

    if (md5sum != null && !Regex("^[a-f0-9]{32}$").matches(md5sum!!)) {
      Log.e(TAG, "An error has occurred, md5sum is not valid.")
      return false
    }

    if (uploadCompleted) {
      Log.i(TAG, "Upload has already been completed.")
      if (!uploadVerified) {
        if (!verifyUpload()) {
          return false
        }
      }
      return true
    }

    val filepath = File(context.filesDir, relativePath)
    if (!filepath.exists()) {
      Log.e(TAG, "File $filepath is registered but does not exist.")
      return true  // Continue with other files.
    }
    if (fileSize == null) {
      val size = filepath.length()
      check(size != 0L) { "Could not determine the file size." }
      fileSize = size
    }

    // Step 1 is computing an md5.
    if (md5sum == null) {
      if (!acquireMd5()) {
        return false
      }
    }
    // Step 2 is to get the upload link.
    if (uploadLink == null) {
      if (!acquireUploadLink()) {
        return false
      }
    }
    // Step 3 is to obtain the sessionLink.
    var createdNewSessionLink = false
    if (sessionLink == null) {
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
      numSavedBytes = sessionState!!
    }
    // Step 5 is to upload the file.
    if (!uploadCompleted) {
      if (!uploadFileToSession(numSavedBytes)) {
        return false
      }
    }
    // Step 6 is to verify with the server that the file is properly uploaded.
    // Note, the md5 has already been verified by the gcs bucket's resumable upload.
    if (!uploadVerified) {
      if (!verifyUpload()) {
        return false
      }
    }
    return true
  }
}

/**
 * class to upload files and key value pairs to the server.
 */
class DataManager(val context: Context) {

  companion object {
    private val TAG = DataManager::class.simpleName
    private val LOGIN_TOKEN_RELATIVE_PATH = "config" + File.separator + "loginToken.txt"

    //private val SERVER = "https://collector-dot-sign-annotation-dev.uc.r.appspot.com"
    val SERVER = "https://localhost:8050"  // TODO use local server for debugging.
    val TRUST_ALL_CERTIFICATES = (SERVER.startsWith("https://localhost"))

    fun setTrustAllCertificates(urlConnection: HttpsURLConnection) {
      // This is a workaround to work with self signed certificates.  Ideally, we would
      // install those self signed certificates as trusted on the Android device, but
      // that turned out to be quite complicated.
      val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        }

        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
      })
      val sslContext = SSLContext.getInstance("SSL")
      sslContext.init(null, trustAllCerts, SecureRandom())

      class TrustAllHostnameVerifier : HostnameVerifier {
        override fun verify(arg0: String, arg1: SSLSession): Boolean {
          return true
        }
      }
      urlConnection.setHostnameVerifier(TrustAllHostnameVerifier())
      urlConnection.sslSocketFactory = sslContext.socketFactory
    }

    /**
     * Get the urlConnection's "input" stream (i.e. the result), whether that is the error
     * stream or the regular stream
     */
    fun getDataStream(urlConnection: HttpURLConnection): InputStream {
      val inputStream: InputStream
      if (urlConnection.responseCode >= 400) {
        inputStream = urlConnection.getErrorStream()
      } else {
        inputStream = urlConnection.getInputStream()
      }
      return inputStream
    }

    fun serverFormPostRequest(url: URL, data: Map<String, String>): Pair<Int, String?> {
      val formData = data.map { (k, v) ->
        URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
      }.joinToString("&").toByteArray(Charsets.UTF_8)
      val (code, output, unused) = serverRequest(
        url,
        "POST",
        mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        formData,
        null
      )
      return Pair(code, output)
    }

    fun serverRequest(
      url: URL, requestMethod: String, headers: Map<String, String>, data: ByteArray,
      outputHeader: String?
    ): Triple<Int, String?, String?> {
      var outputFromHeader: String? = null
      val urlConnection = url.openConnection() as HttpsURLConnection
      if (TRUST_ALL_CERTIFICATES) {
        setTrustAllCertificates(urlConnection)
      }

      var code: Int = -1
      var output: String? = null
      try {
        urlConnection.setDoOutput(true)
        urlConnection.setFixedLengthStreamingMode(data.size)
        urlConnection.setRequestMethod(requestMethod)
        headers.forEach {
          urlConnection.setRequestProperty(it.key, it.value)
        }

        val outputStream = urlConnection.getOutputStream()
        outputStream.write(data)
        outputStream.close()

        if (outputHeader != null) {
          outputFromHeader = urlConnection.getHeaderField(outputHeader)
        }
        urlConnection.headerFields.forEach {
          Log.i(TAG, "header ${it.key} -> ${it.value}")
        }

        code = urlConnection.responseCode
        val inputStream = getDataStream(urlConnection)
        output = inputStream.readBytes().toString(Charsets.UTF_8)
        inputStream.close()
        if (urlConnection.responseCode >= 400) {
          Log.e(TAG, "Response code: $code " + output)
        }
      } catch (e: IOException) {
        Log.e(TAG, "Post request failed: $e")
      } finally {
        urlConnection.disconnect()
      }
      return Triple(code, output, outputFromHeader)
    }

  }

  var loginToken: String? = null
  val LOGIN_TOKEN_FULL_PATH =
    context.getFilesDir().getAbsolutePath() + File.separator + LOGIN_TOKEN_RELATIVE_PATH

  init {
    try {
      val stream = FileInputStream(LOGIN_TOKEN_FULL_PATH)
      loginToken = stream.readBytes().toString(Charsets.UTF_8)
    } catch (e: FileNotFoundException) {
      Log.i(TAG, "loginToken not found.")
    }
  }

  fun deleteLoginToken() {
    File(LOGIN_TOKEN_FULL_PATH).delete()
  }

  fun hasAccount(): Boolean {
    return loginToken != null
  }

  fun createAccount(username: String, adminPassword: String): Boolean {
    Log.i(TAG, "Creating new account for $username")
    if (!Regex("^[a-z][a-z0-9_]{2,}$").matches(username)) {
      Log.e(
        TAG,
        "username must be at least 3 lowercase alphanumeric or underscore " +
            "characters with the first character being a letter."
      )
      return false
    }
    val password = toHex(SecureRandom().generateSeed(32))
    val newLoginToken = makeToken(username, password)
    val adminToken = makeToken("admin", adminPassword)
    val loginTokenPath = File(LOGIN_TOKEN_FULL_PATH)
    if (!loginTokenPath.parentFile.exists()) {
      Log.i(TAG, "creating directory for loginToken.")
      loginTokenPath.parentFile.mkdirs()
    }

    val url = URL(SERVER + "/register_login")
    val (code, unused) =
      serverFormPostRequest(url, mapOf("admin_token" to adminToken, "login_token" to newLoginToken))
    if (code < 200 || code >= 300) {
      return false
    }

    Log.i(TAG, "creating $LOGIN_TOKEN_FULL_PATH")
    val stream = FileOutputStream(LOGIN_TOKEN_FULL_PATH)
    stream.write(newLoginToken.toByteArray(Charsets.UTF_8))
    stream.close()

    loginToken = newLoginToken
    return true
  }

  private suspend fun tryUploadKeyValue(entry: Map.Entry<Preferences.Key<*>, Any>): Boolean {
    if (UploadService.isPaused()) {
      throw InterruptedUploadException("tryUploadKeyValue interrupted.")
    }
    Log.i(TAG, "Uploading key: \"${entry.key}\" with value \"${entry.value}\"")
    val url = URL(SERVER + "/save")
    val (code, unused) =
      serverFormPostRequest(
        url,
        mapOf(
          "login_token" to loginToken!!,
          "key" to entry.key.name as String,
          "value" to entry.value as String
        )
      )
    if (code >= 200 && code < 300) {
      context.dataStore.edit { preferences ->
        preferences.remove(entry.key)
      }
      return true
    } else {
      Log.e(
        TAG,
        "Unable to upload key \"${entry.key}\", value \"${entry.value}\".  Will try again later."
      )
      return false
    }
  }

  fun createNotification(title: String, message: String): Notification {
    return Notification.Builder(context, UploadService.NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(R.drawable.upload_service_notification_icon)
      .setContentTitle(title)
      .setContentText(message)
      .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
      .setTicker("$title: $message")
      .build()
  }

  suspend fun uploadData(notificationManager: NotificationManager): Boolean {
    if (loginToken == null) {
      Log.e(TAG, "No loginToken present, can not upload key, value.")
      return false
    }
    val entries = context.dataStore.data
      .map {
        it.asMap().entries
      }.firstOrNull()
    if (entries == null) {
      Log.i(TAG, "entries was null")
      return false
    }
    var i = 0
    for (entry in entries.iterator()) {
      notificationManager.notify(
        UploadService.NOTIFICATION_ID,
        createNotification("Uploading key/value", "${i + 1} of ${entries.size}")
      )
      if (!tryUploadKeyValue(entry)) {
        return false
      }
      i += 1
    }
    val fileEntries = context.registerFileStore.data
      .map {
        it.asMap().entries
      }.firstOrNull()
    if (fileEntries == null) {
      Log.i(TAG, "entries was null")
      return false
    }
    i = 0
    for (entry in fileEntries.iterator()) {
      notificationManager.notify(
        UploadService.NOTIFICATION_ID,
        createNotification("Uploading file", "${i + 1} of ${fileEntries.size}")
      )
      Log.i(TAG, "Creating UploadSession for ${entry.key.name}")
      val uploadSession = UploadSession(context, loginToken!!, entry.key.name)
      uploadSession.loadState(entry.value as String)
      if (!uploadSession.tryUploadFile()) {
        return false
      }
      i += 1
    }
    return true
  }

  suspend fun addKeyValue(key: String, value: String) {
    Log.i(TAG, "Storing key: \"$key\", value: \"$value\".")
    val keyObject = stringPreferencesKey(key)
    context.dataStore.edit { preferences ->
      preferences[keyObject] = value
    }
  }


  suspend fun registerFile(relativePath: String) {
    Log.i(TAG, "Register file for upload: \"$relativePath\"")
    val keyObject = stringPreferencesKey(relativePath)
    context.registerFileStore.edit { preferences ->
      preferences[keyObject] = JSONObject().toString()
    }
  }
}
