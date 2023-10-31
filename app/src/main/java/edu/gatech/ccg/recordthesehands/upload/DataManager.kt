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
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionParams
import android.util.Base64
import android.util.Log
import android.widget.TextView
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import edu.gatech.ccg.recordthesehands.Constants.APP_VERSION
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.fromHex
import edu.gatech.ccg.recordthesehands.padZeroes
import edu.gatech.ccg.recordthesehands.toHex
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dataStore")
private val Context.registerFileStore: DataStore<Preferences> by preferencesDataStore(
  name = "registerFileStore"
)
val Context.prefStore: DataStore<Preferences> by preferencesDataStore(
  name = "prefStore"
)

class InterruptedUploadException(message: String) : InterruptedIOException(message)

fun makeToken(username: String, password: String): String {
  val digest = MessageDigest.getInstance("SHA-256")
  var token = "$username:$password"
  for (i in 0..999) {
    token = "$username:" + toHex(digest.digest(token.toByteArray(Charsets.UTF_8)))
  }
  return token
}

class DataManagerReceiver : BroadcastReceiver() {

  companion object {
    private val TAG = DataManagerReceiver::class.simpleName
  }

  override fun onReceive(context: Context, intent: Intent) {
    Log.i(TAG, "received intent")
    check(intent.action == ".upload.SESSION_API_PACKAGE_INSTALLED")
    val extras = intent.extras
    if (extras != null) {
      val status = extras.getInt(PackageInstaller.EXTRA_STATUS)
      val message = extras.getString(PackageInstaller.EXTRA_STATUS_MESSAGE)
      Log.i(TAG, "Got extras: status = $status message = $message")
      if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
        Log.i(TAG, "Pending user action.")
        val confirmIntent = extras.get(Intent.EXTRA_INTENT) as Intent
        confirmIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // TODO This might only work if the activity is in the foreground.
        context.startActivity(confirmIntent)
      }
      if (status == PackageInstaller.STATUS_SUCCESS) {
        val md5KeyObject = stringPreferencesKey("apkDownloadMd5")
        val timestampKeyObject = stringPreferencesKey("apkTimestamp")
        val downloadTimestampKeyObject = stringPreferencesKey("apkDownloadTimestamp")
        val md5 = extras.getString("apkMd5")!!
        val apkTimestamp = extras.getString("apkTimestamp")!!
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        runBlocking {
          context.prefStore.edit { preferences ->
            preferences[md5KeyObject] = md5
            preferences[timestampKeyObject] = apkTimestamp
            preferences[downloadTimestampKeyObject] = timestamp
          }
        }
      }
    } else {
      Log.e(TAG, "No extras in intent.")
    }

  }
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
          "app_version" to APP_VERSION,
          "login_token" to loginToken,
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

    Log.i(TAG, "acquireSessionState: \"${relativePath}\"")
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
          "app_version" to APP_VERSION,
          "login_token" to loginToken,
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
    } else {
      if (filepath.length() != fileSize) {
        Log.e(TAG, "fileSize has changed resetting State.")
        resetState()
        return true  // continue with other files.
      }
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

class DataManagerData() {
  companion object {
    private val TAG = DataManagerData::class.simpleName

    @Volatile
    private var instance: DataManagerData? = null
    fun getInstance(login_token_path: String): DataManagerData {
      val checkInstance = instance
      if (checkInstance != null) {
        return checkInstance
      }

      return synchronized(this) {
        val checkInstanceAgain = instance
        if (checkInstanceAgain != null) {
          checkInstanceAgain
        } else {
          val created = DataManagerData()
          created.initialize(login_token_path)
          instance = created
          created
        }
      }
    }
  }

  val lock = Mutex()
  var uid: String? = null
  var loginToken: String? = null
  var promptsData: Prompts? = null
  var keyValues = mutableMapOf<String, JSONObject>()
  var registeredFiles = mutableMapOf<String, JSONObject>()

  fun initialize(login_token_path: String) {
    try {
      val stream = FileInputStream(login_token_path)
      loginToken = stream.readBytes().toString(Charsets.UTF_8)
    } catch (e: FileNotFoundException) {
      Log.i(TAG, "loginToken not found.")
    }
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

    fun serverGetToFileRequest(
      url: URL, headers: Map<String, String>,
      fileOutputStream: FileOutputStream
    ): Boolean {
      val urlConnection = url.openConnection() as HttpsURLConnection
      if (TRUST_ALL_CERTIFICATES) {
        setTrustAllCertificates(urlConnection)
      }

      var code: Int = -1
      try {
        urlConnection.setDoOutput(false)
        urlConnection.setRequestMethod("GET")
        headers.forEach {
          urlConnection.setRequestProperty(it.key, it.value)
        }

        code = urlConnection.responseCode
        if (code >= 200 && code < 300) {
          val inputStream = getDataStream(urlConnection)
          inputStream.copyTo(fileOutputStream)
          inputStream.close()
        } else if (urlConnection.responseCode >= 400) {
          Log.e(TAG, "Failed to get url.  Response code: $code ")
          return false
        }
      } catch (e: IOException) {
        Log.e(TAG, "Get request failed: $e")
        return false
      } finally {
        urlConnection.disconnect()
      }
      return true
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
        // urlConnection.headerFields.forEach {
        //   Log.d(TAG, "header ${it.key} -> ${it.value}")
        // }

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

  val LOGIN_TOKEN_FULL_PATH =
    context.getFilesDir().getAbsolutePath() + File.separator + LOGIN_TOKEN_RELATIVE_PATH

  val dataManagerData = DataManagerData.getInstance(LOGIN_TOKEN_FULL_PATH)

  fun getUsername(): String? {
    if (dataManagerData.loginToken == null) {
      return null
    }
    return dataManagerData.loginToken!!.split(':', limit = 2)[0]
  }

  suspend fun getPhoneId(): String {
    if (dataManagerData.uid != null) {
      return dataManagerData.uid!!
    }
    dataManagerData.lock.withLock {
      if (dataManagerData.uid != null) {
        return dataManagerData.uid!!
      }
      val keyObject = stringPreferencesKey("uid")
      var uid = context.prefStore.data.map {
        it[keyObject]
      }.firstOrNull()
      if (uid != null) {
        dataManagerData.uid = uid
        return uid!!
      }
      uid = toHex(SecureRandom().generateSeed(4))
      context.prefStore.edit { preferences ->
        preferences[keyObject] = uid!!
      }
      dataManagerData.uid = uid
      return dataManagerData.uid!!
    }
  }

  suspend fun newSessionId(): String {
    val deviceId = getPhoneId()
    val keyObject = stringPreferencesKey("sessionIdIndex")
    val oldValue = context.prefStore.edit { preferences ->
      preferences[keyObject] = ((preferences[keyObject]?.toInt() ?: 0) + 1).toString()
    }
    val sessionIndex = oldValue[keyObject]?.toInt() ?: 0
    return "${deviceId}-s${padZeroes(sessionIndex, 2)}"
  }

  fun deleteLoginToken() {
    File(LOGIN_TOKEN_FULL_PATH).delete()
    dataManagerData.loginToken = null
  }

  fun hasAccount(): Boolean {
    return dataManagerData.loginToken != null
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
    if (!loginTokenPath.parentFile!!.exists()) {
      Log.i(TAG, "creating directory for loginToken.")
      loginTokenPath.parentFile!!.mkdirs()
    }

    val url = URL(SERVER + "/register_login")
    val (code, unused) =
      serverFormPostRequest(url, mapOf(
        "app_version" to APP_VERSION,
        "admin_token" to adminToken,
        "login_token" to newLoginToken))
    if (code < 200 || code >= 300) {
      return false
    }

    Log.i(TAG, "creating $LOGIN_TOKEN_FULL_PATH")
    val stream = FileOutputStream(LOGIN_TOKEN_FULL_PATH)
    stream.write(newLoginToken.toByteArray(Charsets.UTF_8))
    stream.close()

    dataManagerData.loginToken = newLoginToken
    return true
  }

  private suspend fun uploadState(): Boolean {
    if (UploadService.isPaused()) {
      throw InterruptedUploadException("tryUploadKeyValue interrupted.")
    }
    val json = JSONObject()
    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    json.put("timestamp", timestamp)
    json.put("username", getUsername())
    json.put("deviceId", getPhoneId())
    val recordingCountKeyObject = intPreferencesKey("lifetimeRecordingCount")
    val lifetimeRecordingCount = context.prefStore.data
      .map {
        it[recordingCountKeyObject]
      }.firstOrNull() ?: 0L
    json.put("lifetimeRecordingCount", lifetimeRecordingCount)
    val recordingMsKeyObject = longPreferencesKey("lifetimeRecordingMs")
    val lifetimeRecordingMs = context.prefStore.data
      .map {
        it[recordingMsKeyObject]
      }.firstOrNull() ?: 0L
    json.put("lifetimeRecordingMs", lifetimeRecordingMs)

    val keyValuesJson = JSONArray()
    json.put("keyValues", keyValuesJson)
    context.dataStore.data
      .map {
        it.asMap().entries
      }.firstOrNull()?.let {
        for (entry in it.iterator()) {
          val entryJson = JSONObject(entry.value as String)
          entryJson.put("key", entry.key.name)
          keyValuesJson.put(entryJson)
        }
      }

    val filesJson = JSONArray()
    json.put("files", filesJson)
    context.registerFileStore.data
      .map {
        it.asMap().entries
      }.firstOrNull()?.let {
        for (entry in it.iterator()) {
          val entryJson = JSONObject(entry.value as String)
          entryJson.put("filepath", entry.key.name)
          filesJson.put(entryJson)
        }
      }
    json.put("prompts", getPrompts()?.toJson())

    val url = URL(SERVER + "/save_state")
    val (code, unused) =
      serverFormPostRequest(
        url,
        mapOf(
          "app_version" to APP_VERSION,
          "login_token" to dataManagerData.loginToken!!,
          "state" to json.toString(),
        )
      )
    if (code >= 200 && code < 300) {
      return true
    } else {
      Log.e(
        TAG,
        "Unable to save state."
      )
      return false
    }
  }

  private suspend fun tryUploadKeyValues(entries: JSONArray): Boolean {
    if (UploadService.isPaused()) {
      throw InterruptedUploadException("tryUploadKeyValues interrupted.")
    }
    dataManagerData.lock.withLock {
      for (i in 0..entries.length() - 1) {
        Log.i(TAG, "Uploading key: \"${entries.getJSONObject(i).getString("key")}\"")
      }
      val url = URL(SERVER + "/save")
      val (code, unused) =
        serverFormPostRequest(
          url,
          mapOf(
            "app_version" to APP_VERSION,
            "login_token" to dataManagerData.loginToken!!,
            "data" to entries.toString(),
          )
        )
      if (code >= 200 && code < 300) {
        context.dataStore.edit { preferences ->
          for (i in 0..entries.length() - 1) {
            val keyObject = stringPreferencesKey(entries.getJSONObject(i).getString("key"))
            preferences.remove(keyObject)
          }
        }
        return true
      } else {
        Log.e(
          TAG,
          "Unable to upload keys.  Will try again later."
        )
        return false
      }
    }
  }

  private suspend fun tryUploadKeyValue(entry: Map.Entry<Preferences.Key<*>, Any>): Boolean {
    if (UploadService.isPaused()) {
      throw InterruptedUploadException("tryUploadKeyValue interrupted.")
    }
    Log.i(TAG, "Uploading key: \"${entry.key}\"")
    val url = URL(SERVER + "/save")
    val (code, unused) =
      serverFormPostRequest(
        url,
        mapOf(
          "app_version" to APP_VERSION,
          "login_token" to dataManagerData.loginToken!!,
          "key" to entry.key.name,
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

  suspend fun updateApkTimestamp(): Boolean {
    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    val keyObject = stringPreferencesKey("apkInstallTimestamp")
    context.prefStore.edit { preferences ->
      preferences[keyObject] = timestamp
    }
    logToServer("apk installed at timestamp $timestamp")
    return true
  }

  suspend fun updateApp(): Boolean {
    if (UploadService.isPaused()) {
      throw InterruptedUploadException("updateApp was interrupted.")
    }
    Log.i(TAG, "Check for latest apk.")
    val url = URL(DataManager.SERVER + "/update_apk")
    val keyObject = stringPreferencesKey("apkInstallTimestamp")
    var timestamp = context.prefStore.data
      .map {
        it[keyObject]
      }.firstOrNull()
    if (timestamp == null) {
      timestamp = "null"
    }
    val (code, data) = DataManager.serverFormPostRequest(
      url,
      mapOf(
        "app_version" to APP_VERSION,
        "login_token" to dataManagerData.loginToken!!,
        "timestamp" to timestamp!!)
    )
    if (code >= 200 && code < 300) {
      // Check JSON to see if we have the latest version.
    } else {
      Log.e(TAG, "unable download new apk.")
      return false
    }
    return true
  }

  suspend fun uploadData(notificationManager: NotificationManager): Boolean {
    if (dataManagerData.loginToken == null) {
      Log.e(TAG, "No loginToken present, can not upload data.")
      return false
    }
    // First run directives, to make sure we have all the data we need.
    runDirectives()
    val entries = context.dataStore.data
      .map {
        it.asMap().entries
      }.firstOrNull()
    if (entries == null) {
      Log.i(TAG, "entries was null")
      return false
    }
    if (entries.isNotEmpty()) {
      notificationManager.notify(
        UploadService.NOTIFICATION_ID,
        createNotification("Uploading key/values", "${entries.size} key/values uploading")
      )
      val jsonArray = JSONArray()
      var i = 0
      for (entry in entries.iterator()) {
        val jsonEntry = JSONObject()
        jsonEntry.put("key", entry.key.name)
        val data = JSONObject(entry.value as String).get("data")
        jsonEntry.put("value", data)
        jsonArray.put(jsonEntry)
        i += 1
        if (i >= 500) {
          break
        }
      }
      if (!tryUploadKeyValues(jsonArray)) {
        return false
      }
    }
    val fileEntries = context.registerFileStore.data
      .map {
        it.asMap().entries
      }.firstOrNull()
    if (fileEntries == null) {
      Log.i(TAG, "entries was null")
      return false
    }
    var i = 0
    for (entry in fileEntries.iterator()) {
      notificationManager.notify(
        UploadService.NOTIFICATION_ID,
        createNotification("Uploading file", "${i + 1} of ${fileEntries.size}")
      )
      Log.i(TAG, "Creating UploadSession for ${entry.key.name}")
      val uploadSession = UploadSession(context, dataManagerData.loginToken!!, entry.key.name)
      uploadSession.loadState(entry.value as String)
      dataManagerData.lock.withLock {
        if (!uploadSession.tryUploadFile()) {
          return false
        }
      }
      i += 1
    }
    return true
  }
  suspend fun waitForDataLock() {
    dataManagerData.lock.lock()
    dataManagerData.lock.unlock()
  }

  private fun directiveCompleted(id: String): Boolean {
    Log.i(TAG, "Marking directive completed.")
    val url = URL(DataManager.SERVER + "/directive_completed")
    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    val (code, data) = DataManager.serverFormPostRequest(
      url,
      mapOf(
        "app_version" to APP_VERSION,
        "login_token" to dataManagerData.loginToken!!,
        "id" to id, "timestamp" to timestamp)
    )
    if (code >= 200 && code < 300) {
    } else {
      Log.e(TAG, "unable to mark directive completed.")
      return false
    }
    return true
  }

  suspend fun resetStatistics() {
    val recordingCountKeyObject = intPreferencesKey("lifetimeRecordingCount")
    val recordingMsKeyObject = longPreferencesKey("lifetimeRecordingMs")
    context.prefStore.edit { preferences ->
      preferences.remove(recordingCountKeyObject)
      preferences.remove(recordingMsKeyObject)
    }
  }

  private suspend fun executeDirective(
    id: String, op: String, value: String,
    apkData: JSONObject
  ): Boolean {
    if (op == "noop") {
      Log.i(TAG, "executing noop directive.")
      if (!directiveCompleted(id)) {
        return false
      }
    } else if (op == "changeUser") {
      Log.i(TAG, "Changing username.")
      val json = JSONObject(value)
      val newLoginToken = json.getString("loginToken")
      val stream = FileOutputStream(LOGIN_TOKEN_FULL_PATH)
      Log.i(TAG, "new loginToken ${dataManagerData.loginToken}")
      stream.write(newLoginToken.toByteArray(Charsets.UTF_8))
      stream.close()
      resetStatistics()
      directiveCompleted(id)  // Use the old loginToken.
      dataManagerData.loginToken = newLoginToken
      return false  // Ignore further directives, the next round will be done with the new login.
    } else if (op == "resetStatistics") {
      resetStatistics()
      if (!directiveCompleted(id)) {
        return false
      }
    } else if (op == "updateApk") {
      val url = URL(SERVER + "/apk")
      Log.i(TAG, "updating apk to $url.")
      val filename = apkData.getString("md5") + ".apk"
      val relativePath = "apk" + File.separator + filename
      val filepath = File(context.filesDir, relativePath)

      if (!filepath.parentFile.exists()) {
        Log.i(TAG, "creating directory ${filepath.parentFile}.")
        filepath.parentFile.mkdirs()
      }
      val fileOutputStream = FileOutputStream(filepath.absolutePath)
      try {
        Log.i(TAG, "downloading apk to $filepath")
        if (!serverGetToFileRequest(url, emptyMap(), fileOutputStream)) {
          return false
        }
      } finally {
        fileOutputStream.close()
      }
      Log.i(TAG, "apk downloaded $filepath with size ${filepath.length()}")

      Log.i(TAG, "installing apk")
      // The apk used must be a signed one with the same signature as the installed app.
      // Even so, a bunch of warnings are displayed to the user, some of which might be
      // able to be suppressed with enough effort.
      // Changing signing certificate requires a reinstall, which wipes all local data
      // including any videos which haven't been uploaded.
      installPackage(relativePath, apkData)
      Log.i(TAG, "finished installing apk")

      if (!directiveCompleted(id)) {
        return false
      }
    } else if (op == "downloadPrompts") {
      val url = URL(SERVER + "/prompts")
      Log.i(TAG, "downloading prompt data at $url.")
      val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
      val relativePath = "prompts" + File.separator + timestamp + ".json"
      val filepath = File(context.filesDir, relativePath)

      if (!filepath.parentFile!!.exists()) {
        Log.i(TAG, "creating directory ${filepath.parentFile}.")
        filepath.parentFile!!.mkdirs()
      }
      Log.i(TAG, "downloading prompt data to $filepath")
      val (code, data) = serverFormPostRequest(
        url,
        mapOf(
          "app_version" to APP_VERSION,
          "login_token" to dataManagerData.loginToken!!)
      )
      if (code >= 200 && code < 300) {
        if (data == null) {
          Log.e(TAG, "data was null.")
          return false
        }
        val fileOutputStream = FileOutputStream(filepath)
        try {
          fileOutputStream.write(data!!.toByteArray(Charsets.UTF_8))
        } finally {
          fileOutputStream.close()
        }
      } else {
        Log.e(TAG, "unable to fetch prompts.")
        return false
      }
      Log.i(TAG, "prompt data downloaded with size ${filepath.length()}")

      val keyObject = stringPreferencesKey("promptsFilename")
      val key2Object = stringPreferencesKey("promptIndex")
      context.prefStore.edit { preferences ->
        preferences[keyObject] = relativePath
        preferences[key2Object] = "0"
      }
      dataManagerData.promptsData = null
      if (!directiveCompleted(id)) {
        return false
      }
    } else if (op == "deleteFile") {
      val json = JSONObject(value)
      val relativePath = json.getString("filepath")
      val filepath = File(context.filesDir, relativePath)

      if (filepath.exists()) {
        filepath.delete()
        logToServer("As directed: Deleted file $relativePath")
      }
      val keyObject = stringPreferencesKey(relativePath)
      context.registerFileStore.edit { preferences ->
        if (preferences.contains(keyObject)) {
          preferences.remove(keyObject)
          logToServer("As directed: Unregistered file $relativePath")
        }
      }
      if (!directiveCompleted(id)) {
        return false
      }
    } else if (op == "uploadState") {
      if (uploadState()) {
        if (!directiveCompleted(id)) {
          return false
        }
        return true
      } else {
        return false
      }
    } else {
      Log.e(TAG, "Unable to understand directive op \"$op\"")
      return false
    }
    return true
  }

  fun installPackage(relativePath: String, apkData: JSONObject) {
    val packageInstaller = context.packageManager.packageInstaller
    val sessionParams = SessionParams(SessionParams.MODE_FULL_INSTALL)
    sessionParams.setRequireUserAction(SessionParams.USER_ACTION_NOT_REQUIRED)
    val sessionId = packageInstaller.createSession(sessionParams)
    val session = packageInstaller.openSession(sessionId)
    var out: OutputStream? = null
    val filepath = File(context.filesDir, relativePath)
    out = session.openWrite("package", 0, filepath.length())
    val inputStream = FileInputStream(filepath)
    inputStream.copyTo(out)
    session.fsync(out)
    inputStream.close()
    out.close()

    val intent = Intent(context, DataManagerReceiver::class.java)
    intent.setAction(".upload.SESSION_API_PACKAGE_INSTALLED")
    intent.putExtra("apkMd5", apkData.getString("md5"))
    intent.putExtra("apkTimestamp", apkData.getString("timestamp"))
    val pendingIntent = PendingIntent.getBroadcast(
      context,
      sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )
    try {
      session.commit(pendingIntent.intentSender)
      session.close()
    } catch (e: Exception) {
      Log.i(TAG, "" + e.stackTrace)
    }
  }

  suspend fun runDirectives(): Boolean {
    if (dataManagerData.loginToken == null) {
      Log.e(TAG, "No loginToken present, can not download data.")
      return false
    }
    if (UploadService.isPaused()) {
      throw InterruptedUploadException("runDirective was interrupted.")
    }
    Log.i(TAG, "Download the directives from server.")
    val url = URL(SERVER + "/directives")
    val (code, data) = serverFormPostRequest(
      url,
      mapOf(
        "app_version" to APP_VERSION,
        "login_token" to dataManagerData.loginToken!!)
    )
    if (code >= 200 && code < 300) {
      if (data == null) {
        Log.e(TAG, "data was null.")
        return false
      }
      val json = JSONObject(data)
      val array = json.getJSONArray("directives")
      val apkData = json.getJSONObject("apk")
      for (i in 0..array.length() - 1) {
        val directive = array.getJSONObject(i)
        if (!executeDirective(
            directive.getString("id"),
            directive.getString("op"),
            directive.getString("value"),
            apkData
          )
        ) {
          return false
        }
      }
    } else {
      Log.e(TAG, "unable to fetch directives.")
      return false
    }
    return true
  }

  fun logToServer(message: String) {
    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    logToServerAtTimestamp(timestamp, message)
  }

  fun logToServerAtTimestamp(timestamp: String, message: String) {
    addKeyValue("log-$timestamp", message)
  }

  /**
   * Save a key value pair to the server.  Re-using the same key will overwrite
   * the existing value (either before uploading to the server, or if it has already
   * been uploaded then on the server).
   *
   * No data will be sent to the server unless persistData() is called (which stores a snapshot
   * of the key values to a dataStore).
   */
  fun addKeyValue(key: String, value: String) {
    Log.i(TAG, "Storing key: \"$key\", value: \"$value\".")
    val saveJson = JSONObject()
    saveJson.put("data", value)
    dataManagerData.keyValues[key] = saveJson
  }

  fun addKeyValue(key: String, json: JSONObject) {
    Log.i(TAG, "Storing key: \"$key\", json:\n${json.toString(2)}")
    val saveJson = JSONObject()
    saveJson.put("data", json)
    dataManagerData.keyValues[key] = saveJson
  }

  fun registerFile(relativePath: String) {
    Log.i(TAG, "Register file for upload: \"$relativePath\"")
    dataManagerData.registeredFiles[relativePath] = JSONObject()
  }

  /**
   * Persist the key value and registered files data and upload it to the server when convenient.
   *
   * If addKeyValue is called while this function is executing then either of the new or old
   * values may be persisted.  If clearData is true then the data will be deleted (regardless of
   * whether new data was added).
   */
  suspend fun persistData(clearData: Boolean) {
    dataManagerData.lock.withLock {
      Log.i(TAG, "Starting to persist data. clearData == $clearData")
      context.dataStore.edit { preferences ->
        dataManagerData.keyValues.forEach {
          val keyObject = stringPreferencesKey(it.key)
          preferences[keyObject] = it.value.toString()
        }
      }
      if (clearData) {
        dataManagerData.keyValues.clear()
      }
      context.registerFileStore.edit { preferences ->
        dataManagerData.registeredFiles.forEach {
          val keyObject = stringPreferencesKey(it.key)
          preferences[keyObject] = it.value.toString()
        }
      }
      if (clearData) {
        dataManagerData.registeredFiles.clear()
      }
      Log.i(TAG, "Finished persisting data. clearData == $clearData")
    }
  }

  suspend fun getPrompts(): Prompts? {
    if (dataManagerData.promptsData != null) {
      return dataManagerData.promptsData
    }
    dataManagerData.lock.withLock {
      if (dataManagerData.promptsData != null) {
        return dataManagerData.promptsData
      }
      val newPromptsData = Prompts(context)
      if (!newPromptsData.initialize()) {
        return null
      }
      dataManagerData.promptsData = newPromptsData
      return dataManagerData.promptsData
    }
  }

  suspend fun updateLifetimeStatistics(sessionLength: Duration) {
    val recordingCountKeyObject = intPreferencesKey("lifetimeRecordingCount")
    val recordingMsKeyObject = longPreferencesKey("lifetimeRecordingMs")
    context.prefStore.edit { preferences ->
      preferences[recordingCountKeyObject] = (preferences[recordingCountKeyObject] ?: 0 ) + 1
      preferences[recordingMsKeyObject] =
          (preferences[recordingMsKeyObject] ?: 0 ) + sessionLength.toMillis()
    }
  }
}

class Prompt(val index: Int, val key: String, val prompt: String) {
  fun toJson(): JSONObject {
    val json = JSONObject()
    json.put("index", index)
    json.put("key", key)
    json.put("prompt", prompt)
    return json
  }

}

class Prompts(val context: Context) {
  companion object {
    private val TAG = Prompts::class.simpleName
  }

  var array = ArrayList<Prompt>()
  var promptIndex = -1

  suspend fun initialize(): Boolean {
    Log.i(TAG, "Getting prompt data.")
    val keyObject = stringPreferencesKey("promptsFilename")
    val key2Object = stringPreferencesKey("promptIndex")
    val promptsData = context.prefStore.data.map {
      Pair(it[keyObject], it[key2Object])
    }.firstOrNull() ?: return false
    val promptsRelativePath = promptsData.first ?: return false
    promptIndex = (promptsData.second ?: return false).toInt()
    try {
      val promptsJson = JSONObject(File(context.filesDir, promptsRelativePath).readText())
      val promptsJsonArray = promptsJson.getJSONArray("prompts")
      array.ensureCapacity(promptsJsonArray.length())
      for (i in 0..promptsJsonArray.length() - 1) {
        val data = promptsJsonArray.getJSONObject(i)
        array.add(Prompt(i, data.getString("key"), data.getString("prompt")))
      }
    } catch (e: JSONException) {
      Log.e(TAG, "failed to load prompts, encountered JSONException $e")
      return false
    }
    return true
  }

  suspend fun savePromptIndex() {
    val key2Object = stringPreferencesKey("promptIndex")
    context.prefStore.edit { preferences ->
      preferences[key2Object] = promptIndex.toString()
    }
  }

  fun toJson(): JSONObject {
    val json = JSONObject()
    json.put("promptIndex", promptIndex)
    val arrayJson = JSONArray()
    for (prompt in array) {
      arrayJson.put(prompt.toJson())
    }
    json.put("prompts", arrayJson)
    return json
  }
}
