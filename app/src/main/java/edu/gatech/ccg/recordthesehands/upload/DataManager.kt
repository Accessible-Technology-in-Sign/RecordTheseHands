/**
 * This file is part of Record These Hands, licensed under the MIT license.
 *
 * Copyright (c) 2023-2025
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
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.LiveData
import edu.gatech.ccg.recordthesehands.Constants.APP_VERSION
import edu.gatech.ccg.recordthesehands.Constants.PROMPTS_FILENAME
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_NOTIFICATION_CHANNEL_ID
import edu.gatech.ccg.recordthesehands.Constants.UPLOAD_NOTIFICATION_ID
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.padZeroes
import edu.gatech.ccg.recordthesehands.recording.ClipDetails
import edu.gatech.ccg.recordthesehands.recording.RecordingSessionInfo
import edu.gatech.ccg.recordthesehands.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
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
        val confirmIntent = extras.getParcelable(Intent.EXTRA_INTENT, Intent::class.java)
        if (confirmIntent != null) {
          confirmIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
          // TODO This might only work if the activity is in the foreground.
          context.startActivity(confirmIntent)
        }
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


/**
 * Manages all data interactions with the backend server, local storage, and in-memory state.
 * This class is the central point of control for fetching prompts, uploading user data and videos,
 * managing user accounts, and handling application updates.
 *
 * <h3>Overview</h3>
 *
 * `DataManager` is responsible for a wide range of data-related tasks:
 * <ul>
 *     <li><b>User Authentication:</b> Handles account creation and login token management.</li>
 *     <li><b>Data Persistence:</b> Saves and retrieves data from local storage using `DataStore`.
 *     This includes user preferences, prompt progress, and queued data for upload.</li>
 *     <li><b>Server Communication:</b> Manages all HTTP requests to the backend server for
 *     uploading data, downloading prompts, and receiving directives.</li>
 *     <li><b>State Management:</b> Maintains the application's state, particularly the `PromptState`,
 *     which is exposed to the UI via `LiveData`.</li>
 *     <li><b>File Management:</b> Handles the registration and upload of video files, as well as
 *     downloading and managing resources like APK updates and prompt assets.</li>
 * </ul>
 *
 * <h3>Interaction with `DataManagerData` and `PromptState`</h3>
 *
 * To ensure thread safety and a single source of truth, `DataManager` relies on the `DataManagerData`
 * singleton object. `DataManagerData` holds the in-memory state of the application, including:
 * <ul>
 *     <li>`loginToken`: The user's authentication token.</li>
 *     <li>`promptStateContainer`: An instance of `PromptState` which holds all data related to
 *     prompts, user progress, and device information.</li>
 *     <li>`lock`: A `Mutex` that protects access to all mutable state within `DataManagerData`.
 *     All functions that modify or read this state must acquire this lock.</li>
 *     <li>`_promptState` and `_serverStatus`: Private `MutableLiveData` objects that are updated
 *     to notify the UI of state changes.</li>
 * </ul>
 *
 * `PromptState` is an immutable data class that represents a snapshot of the user's progress,
 * the available prompts, and other session-related information. When the state changes, a new
 * `PromptState` object is created and posted to the `_promptState` `LiveData`, which in turn
 * updates the UI. This ensures that the UI always reflects the current state of the application
 * in a thread-safe manner.
 *
 * <h3>Locking and Thread Safety</h3>
 *
 * All public functions that access or modify the shared state in `DataManagerData` acquire a lock
 * using `dataManagerData.lock.withLock { ... }`. This prevents race conditions and ensures that
 * all data operations are atomic. As a result, any function that acquires this lock may block if
 * another thread is currently holding the lock. The Kdoc for each function explicitly mentions
 * whether it acquires the lock and has the potential to block.
 *
 * @param context The application context, used for accessing resources and local storage.
 */
class DataManager private constructor(val context: Context) {

  companion object {
    @Volatile
    private var INSTANCE: DataManager? = null
    private val TAG = DataManager::class.simpleName
    private val LOGIN_TOKEN_RELATIVE_PATH = "config" + File.separator + "loginToken.txt"

    private const val TUTORIAL_MODE_DEFAULT = true
    fun getInstance(context: Context): DataManager {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: DataManager(context.applicationContext).also {
          INSTANCE = it
        }
      }
    }
  }

  val LOGIN_TOKEN_FULL_PATH =
    context.filesDir.absolutePath + File.separator + LOGIN_TOKEN_RELATIVE_PATH

  val dataManagerData = DataManagerData

  // Public getters for LiveData now point to the singleton instance.
  val serverStatus: LiveData<Boolean> get() = dataManagerData.serverStatus
  val promptState: LiveData<PromptState> get() = dataManagerData.promptState

  init {
    initializeData()
  }

  /**
   * Initializes the data manager's state from persistent storage.
   * This function is safe to call multiple times, as it uses an atomic boolean to ensure that
   * the initialization process only runs once.
   *
   * It launches a coroutine on the IO dispatcher to perform the actual initialization.
   * The launched coroutine will acquire the data lock, but this function returns immediately
   * and does not block.
   */
  private fun initializeData() {
    if (dataManagerData.initializationStarted.compareAndSet(false, true)) {
      CoroutineScope(Dispatchers.IO).launch {
        dataManagerData.lock.withLock {
          reinitializeDataUnderLock()
        }
      }
    }
  }

  /**
   * Reloads all application state from disk and updates the in-memory `PromptState`.
   * This function is the core of the initialization process. It reads the login token,
   * tutorial mode, prompt collection, prompt progress, and other settings from `DataStore`
   * and the local filesystem. It then constructs a new `PromptState` and posts it to the
   * `LiveData` to update the UI.
   *
   * This function **must** be called while holding the data lock to ensure thread safety.
   * It performs file I/O and can block for a significant amount of time.
   */
  private suspend fun reinitializeDataUnderLock() {
    try {
      FileInputStream(LOGIN_TOKEN_FULL_PATH).use { stream ->
        dataManagerData.loginToken = stream.readBytes().toString(Charsets.UTF_8)
      }
    } catch (e: FileNotFoundException) {
      Log.i(TAG, "loginToken not found.")
    }

    val tutorialMode = getTutorialModeFromPrefStore()
    val promptsCollection = getPromptsCollectionFromDisk()
    val promptProgress = getPromptProgressFromPrefStore()
    val currentSectionName = getCurrentSectionNameFromPrefStore()
    val username = getUsernameUnderLock()

    // Special handling for first-time device ID initialization.
    val deviceIdKey = stringPreferencesKey("deviceId")
    var deviceId = context.prefStore.data.map { it[deviceIdKey] }.firstOrNull()
    if (deviceId == null) {
      deviceId = toHex(SecureRandom().generateSeed(4))
      context.prefStore.edit { preferences ->
        preferences[deviceIdKey] = deviceId
      }
    }

    val initialState = PromptState(
      tutorialMode = tutorialMode,
      promptsCollection = promptsCollection,
      promptProgress = promptProgress,
      currentSectionName = currentSectionName,
      username = username,
      deviceId = deviceId,
    )
    updatePromptStateAndPost(initialState)
  }

  /**
   * Checks if a backend server address is defined in the string resources.
   * This allows the application to gracefully handle builds that do not include a server URL,
   * disabling server communication features.
   *
   * This function does not acquire the data lock and will not block.
   *
   * @return `true` if the `backend_server` string resource exists, `false` otherwise.
   */
  fun hasServer(): Boolean {
    val serverStringId = context.resources.getIdentifier(
      "backend_server",
      "string",
      context.packageName
    )
    return serverStringId != 0
  }

  /**
   * Get the base address of the backend server.
   * This address is defined in a string resource (`R.string.backend_server`).
   * If the resource is not present, this function will throw an `IllegalStateException`.
   * Use [hasServer] to check for the existence of the server address before calling this.
   *
   * This function does not acquire the data lock and will not block.
   *
   * @return The base URL of the backend server as a `String`.
   * @throws IllegalStateException if the `backend_server` string resource is not defined.
   */
  fun getServer(): String {
    val serverStringId = context.resources.getIdentifier(
      "backend_server",
      "string",
      context.packageName
    )
    check(serverStringId != 0) { "backend_server is not defined in a resource." }
    return context.resources.getString(serverStringId)
  }

  /**
   * Determines whether a given URL should bypass standard SSL certificate checks.
   * This is used to trust self-signed certificates for local development servers (e.g.,
   * `localhost` or `10.0.2.2` for the Android emulator). The list of trustable hosts is
   * defined in the `trustable_hosts` string resource.
   *
   * This function should only be used for debugging and development purposes.
   *
   * This function does not acquire the data lock and will not block.
   *
   * @param url The URL to check.
   * @return `true` if the URL's host is in the list of trustable hosts, `false` otherwise.
   */
  private fun shouldTrustUrl(url: URL): Boolean {
    val trustable = context.resources.getIdentifier(
      "trustable_hosts",
      "string",
      context.packageName
    )
    if (trustable == 0) {
      return false
    }

    val host = url.host
    if (host == null) {
      return false
    }
    for (trustable_host in context.resources.getString(trustable).split(',')) {
      if (host == trustable_host) {
        return true
      }
    }
    return false
  }

  /**
   * Configures an `HttpsURLConnection` to trust all SSL certificates, including self-signed ones.
   * This method installs a custom `X509TrustManager` and `HostnameVerifier` that do not
   * perform any validation.
   *
   * **Warning:** This should only be called on connections to servers that are implicitly
   * trusted, such as a local development server. Using this in a production environment is a
   * significant security risk.
   *
   * This function does not acquire the data lock and will not block.
   *
   * @param urlConnection The connection to configure.
   */
  private fun setTrustAllCertificates(urlConnection: HttpsURLConnection) {
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
   * Sets the appropriate level of trust for a given `HttpsURLConnection`.
   * If the connection's URL is determined to be trustable by [shouldTrustUrl], this function
   * will call [setTrustAllCertificates] to bypass SSL validation. Otherwise, the default
   * trust settings are used.
   *
   * This function does not acquire the data lock and will not block.
   *
   * @param urlConnection The connection to configure.
   */
  fun setAppropriateTrust(urlConnection: HttpsURLConnection) {
    if (shouldTrustUrl(urlConnection.url)) {
      setTrustAllCertificates(urlConnection)
    }
  }


  /**
   * Gets the appropriate input stream from an `HttpURLConnection`.
   * If the response code indicates an error (400 or greater), it returns the `errorStream`.
   * Otherwise, it returns the `inputStream`. This is a convenience method to handle both
   * successful and failed HTTP responses.
   *
   * This function does not acquire the data lock and will not block.
   *
   * @param urlConnection The connection to get the stream from.
   * @return The `InputStream` containing the server's response body.
   */
  fun getDataStream(urlConnection: HttpURLConnection): InputStream {
    val inputStream: InputStream
    if (urlConnection.responseCode >= 400) {
      inputStream = urlConnection.errorStream
    } else {
      inputStream = urlConnection.getInputStream()
    }
    return inputStream
  }

  /**
   * Performs a server request with `application/x-www-form-urlencoded` data.
   * This function constructs a form-encoded string from the provided data map, and then
   * calls [serverRequest] to perform the POST request.
   *
   * This function does not acquire the data lock, but it performs a network
   * request and may block for a significant amount of time.
   *
   * @param url The URL to send the request to.
   * @param data A map of key-value pairs to be form-encoded and sent as the request body.
   * @return A `Pair` containing the HTTP response code and the response body as a `String`.
   */
  fun serverFormPostRequest(url: URL, data: Map<String, String>): Pair<Int, String?> {
    val formData = data.map { (k, v) ->
      URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
    }.joinToString("&").toByteArray(Charsets.UTF_8)
    val (code, output, _) = serverRequest(
      url,
      "POST",
      mapOf("Content-Type" to "application/x-www-form-urlencoded"),
      formData,
      null
    )
    return Pair(code, output)
  }

  /**
   * Performs a GET request to a server and streams the response body directly to a file.
   * This is useful for downloading large files without loading the entire content into memory.
   *
   * This function does not acquire the data lock, but it performs a network
   * request and file I/O, and may block for a significant amount of time.
   *
   * @param url The URL to download from.
   * @param headers A map of HTTP headers to include in the request.
   * @param fileOutputStream The `FileOutputStream` to write the response body to. The stream
   * is not closed by this function.
   * @return `true` if the download was successful (HTTP 2xx), `false` otherwise.
   */
  fun serverGetToFileRequest(
    url: URL, headers: Map<String, String>,
    fileOutputStream: FileOutputStream
  ): Boolean {
    val urlConnection = url.openConnection() as HttpsURLConnection
    setAppropriateTrust(urlConnection)

    var code: Int = -1
    var interrupted = false
    try {
      urlConnection.setDoOutput(false)
      urlConnection.requestMethod = "GET"
      headers.forEach {
        urlConnection.setRequestProperty(it.key, it.value)
      }

      code = urlConnection.responseCode
      if (code >= 200 && code < 300) {
        getDataStream(urlConnection).use { inputStream ->
          inputStream.copyTo(fileOutputStream)
        }
      } else if (urlConnection.responseCode >= 400) {
        Log.e(TAG, "Failed to get url.  Response code: $code ")
        return false
      }
    } catch (e: InterruptedUploadException) {
      interrupted = true
      throw e
    } catch (e: IOException) {
      Log.e(TAG, "Get request failed: $e")
      return false
    } finally {
      if (!interrupted) {
        dataManagerData._serverStatus.postValue(code >= 200 && code < 400)
      }
      urlConnection.disconnect()
    }
    return true
  }

  /**
   * A generic function for making HTTP requests to the server.
   * This function handles the setup of the `HttpsURLConnection`, sets headers, writes the
   * request body, and reads the response. It also handles setting the appropriate trust level
   * for the connection and updates the server status `LiveData`.
   *
   * This function does not acquire the data lock, but it performs a network
   * request and may block for a significant amount of time.
   *
   * @param url The URL for the request.
   * @param requestMethod The HTTP method (e.g., "POST", "PUT", "GET").
   * @param headers A map of request headers.
   * @param data The request body as a `ByteArray`.
   * @param outputHeader If not null, the name of a response header to extract and return.
   * @return A `Triple` containing:
   *   - The HTTP response code.
   *   - The response body as a `String`.
   *   - The value of the `outputHeader` if requested, otherwise `null`.
   */
  fun serverRequest(
    url: URL, requestMethod: String, headers: Map<String, String>, data: ByteArray,
    outputHeader: String?
  ): Triple<Int, String?, String?> {
    var outputFromHeader: String? = null
    val urlConnection = url.openConnection() as HttpsURLConnection
    setAppropriateTrust(urlConnection)

    var code: Int = -1
    var output: String? = null
    var interrupted = false
    try {
      urlConnection.setDoOutput(true)
      urlConnection.setFixedLengthStreamingMode(data.size)
      urlConnection.requestMethod = requestMethod
      headers.forEach {
        urlConnection.setRequestProperty(it.key, it.value)
      }

      urlConnection.outputStream.use { it.write(data) }

      if (outputHeader != null) {
        outputFromHeader = urlConnection.getHeaderField(outputHeader)
      }
      // urlConnection.headerFields.forEach {
      //   Log.d(TAG, "header ${it.key} -> ${it.value}")
      // }

      code = urlConnection.responseCode
      getDataStream(urlConnection).use { inputStream ->
        output = inputStream.readBytes().toString(Charsets.UTF_8)
      }

      if (urlConnection.responseCode >= 400) {
        Log.e(TAG, "Response code: $code " + output)
      }
    } catch (e: InterruptedUploadException) {
      interrupted = true
      throw e
    } catch (e: IOException) {
      Log.e(TAG, "Post request failed: $e")
    } finally {
      if (!interrupted) {
        dataManagerData._serverStatus.postValue(code >= 200 && code < 400)
      }
      urlConnection.disconnect()
    }
    return Triple(code, output, outputFromHeader)
  }

  /**
   * Gets the current user's username from the in-memory login token.
   *
   * This function parses the username from the `loginToken` string.
   *
   * This function acquires the data lock and may block if another thread is holding the lock.
   *
   * @return The username as a `String`, or `null` if the user is not logged in.
   */
  suspend fun getUsername(): String? {
    dataManagerData.lock.withLock {
      return getUsernameUnderLock()
    }
  }

  /**
   * Gets the current user's username from the in-memory login token.
   *
   * This function **must** be called while holding the data lock.
   *
   * @return The username as a `String`, or `null` if the user is not logged in.
   */
  private fun getUsernameUnderLock(): String? {
    if (dataManagerData.loginToken == null) {
      return null
    }
    return dataManagerData.loginToken!!.split(':', limit = 2)[0]
  }

  /**
   * Gets the unique device ID.
   * This function retrieves the device ID from the in-memory state.
   *
   * This function acquires the data lock and may block if another thread is holding the lock.
   *
   * @return The device ID as a `String`.
   * @throws IllegalStateException if the device ID has not been initialized.
   */
  suspend fun getDeviceId(): String {
    dataManagerData.lock.withLock {
      return getDeviceIdUnderLock()
    }
  }

  /**
   * Gets the unique device ID from the in-memory state.
   *
   * This function **must** be called while holding the data lock.
   *
   * @return The device ID as a `String`.
   * @throws IllegalStateException if the device ID has not been initialized.
   */
  private suspend fun getDeviceIdUnderLock(): String {
    // The initial value is loaded into the container by initializePromptState.
    // Subsequent reads should come directly from the container.
    return dataManagerData.promptStateContainer?.deviceId
      ?: throw IllegalStateException("Device ID not initialized.")
  }

  /**
   * Sets the unique device ID.
   * This function updates the device ID in both the in-memory state and the persistent
   * `DataStore`.
   *
   * This function acquires the data lock and may block if another thread is holding the lock.
   * It also performs a write to `DataStore`, which can block for a short time.
   *
   * @param deviceId The new device ID.
   * @throws IllegalStateException if the prompt state has not been initialized.
   */
  suspend fun setDeviceId(deviceId: String) {
    dataManagerData.lock.withLock {
      val currentState = dataManagerData.promptStateContainer
        ?: throw IllegalStateException("Prompt state not initialized before setting device ID.")
      updatePromptStateAndPost(currentState.copy(deviceId = deviceId))

      val keyObject = stringPreferencesKey("deviceId")
      context.prefStore.edit {
        it[keyObject] = deviceId
      }
    }
  }

  /**
   * Retrieves the tutorial mode setting directly from the persistent `DataStore`.
   * If the setting is not found, it returns the default value `TUTORIAL_MODE_DEFAULT`.
   *
   * This function does not acquire the data lock. It reads from `DataStore` and may block
   * for a short time.
   *
   * @return The current tutorial mode setting from `DataStore`.
   */
  private suspend fun getTutorialModeFromPrefStore(): Boolean {
    val keyObject = booleanPreferencesKey("tutorialMode")
    return context.prefStore.data.map {
      it[keyObject]
    }.firstOrNull() ?: TUTORIAL_MODE_DEFAULT
  }

  /**
   * Gets the current tutorial mode from the in-memory state.
   *
   * This function acquires the data lock and may block if another thread is holding the lock.
   *
   * @return The current tutorial mode as a `Boolean`.
   */
  suspend fun getTutorialMode(): Boolean {
    dataManagerData.lock.withLock {
      return getTutorialModeUnderLock()
    }
  }

  /**
   * Gets the current tutorial mode from the in-memory state.
   *
   * This function **must** be called while holding the data lock.
   *
   * @return The current tutorial mode as a `Boolean`.
   */
  private suspend fun getTutorialModeUnderLock(): Boolean {
    // Read directly from the in-memory state container.
    return dataManagerData.promptStateContainer?.tutorialMode ?: TUTORIAL_MODE_DEFAULT
  }

  /**
   * Sets the tutorial mode.
   * This function updates the tutorial mode in both the in-memory state and the persistent
   * `DataStore`.
   *
   * This function acquires the data lock and may block if another thread is holding the lock.
   * It also performs a write to `DataStore`, which can block for a short time.
   *
   * @param mode The new tutorial mode.
   */
  suspend fun setTutorialMode(mode: Boolean) {
    Log.d(TAG, "setTutorialMode($mode)")
    dataManagerData.lock.withLock {
      return setTutorialModeUnderLock(mode)
    }
  }

  /**
   * Sets the tutorial mode.
   * This function updates the tutorial mode in both the in-memory state and the persistent
   * `DataStore`.
   *
   * This function **must** be called while holding the data lock.
   * It performs a write to `DataStore`, which can block for a short time.
   *
   * @param mode The new tutorial mode.
   * @throws IllegalStateException if the prompt state has not been initialized.
   */
  private suspend fun setTutorialModeUnderLock(mode: Boolean) {
    val currentState = dataManagerData.promptStateContainer
      ?: throw IllegalStateException("Attempted to set tutorial mode before prompt state was initialized.")
    updatePromptStateAndPost(currentState.copy(tutorialMode = mode))
    val keyObject = booleanPreferencesKey("tutorialMode")
    context.prefStore.edit {
      it[keyObject] = mode
    }
  }

  /**
   * Retrieves the current section name directly from the persistent `DataStore`.
   *
   * This function does not acquire the data lock. It reads from `DataStore` and may block
   * for a short time.
   *
   * @return The current section name as a `String`, or `null` if it's not set.
   */
  private suspend fun getCurrentSectionNameFromPrefStore(): String? {
    val keyObject = stringPreferencesKey("currentSectionName")
    return context.prefStore.data.map {
      it[keyObject]
    }.firstOrNull()
  }

  /**
   * Gets the current section name from the in-memory state.
   *
   * This function acquires the data lock and may block if another thread is holding the lock.
   *
   * @return The current section name as a `String`, or `null` if it's not set.
   */
  suspend fun getCurrentSectionName(): String? {
    dataManagerData.lock.withLock {
      return getCurrentSectionNameUnderLock()
    }
  }

  /**
   * Gets the current section name from the in-memory state.
   *
   * This function **must** be called while holding the data lock.
   *
   * @return The current section name as a `String`, or `null` if it's not set.
   */
  private suspend fun getCurrentSectionNameUnderLock(): String? {
    return dataManagerData.promptStateContainer?.currentSectionName
  }

  /**
   * Sets the current active section.
   * This function updates the section name in both the in-memory state and the persistent
   * `DataStore`.
   *
   * This function acquires the data lock and may block if another thread is holding the lock.
   * It also performs a write to `DataStore`, which can block for a short time.
   *
   * @param sectionName The name of the section to set as current.
   */
  suspend fun setCurrentSection(sectionName: String) {
    dataManagerData.lock.withLock {
      setCurrentSectionUnderLock(sectionName)
    }
  }

  /**
   * Sets the current active section.
   * This function updates the section name in both the in-memory state and the persistent
   * `DataStore`.
   *
   * This function **must** be called while holding the data lock.
   * It performs a write to `DataStore`, which can block for a short time.
   *
   * @param sectionName The name of the section to set as current.
   * @throws IllegalStateException if the prompt state has not been initialized.
   */
  private suspend fun setCurrentSectionUnderLock(sectionName: String) {
    val currentState = dataManagerData.promptStateContainer
      ?: throw IllegalStateException("Attempted to set current section before prompt state was initialized.")
    updatePromptStateAndPost(currentState.copy(currentSectionName = sectionName))
    val keyObject = stringPreferencesKey("currentSectionName")
    context.prefStore.edit {
      it[keyObject] = sectionName
    }
  }

  /**
   * Retrieves the user's prompt progress from the persistent `DataStore`.
   * The progress is stored as a JSON string, which is parsed into a nested map structure.
   *
   * This function does not acquire the data lock. It reads from `DataStore` and may block
   * for a short time.
   *
   * @return A map where the key is the section name and the value is another map
   * containing the progress for that section (e.g., "mainIndex" and "tutorialIndex").
   * Returns an empty map if no progress is saved.
   */
  private suspend fun getPromptProgressFromPrefStore(): Map<String, Map<String, Int>> {
    val keyObject = stringPreferencesKey("promptProgress")
    val jsonString = context.prefStore.data.map {
      it[keyObject]
    }.firstOrNull() ?: return emptyMap()

    val progressMap = mutableMapOf<String, Map<String, Int>>()
    val json = JSONObject(jsonString)
    json.keys().forEach { sectionName ->
      val sectionJson = json.getJSONObject(sectionName)
      val sectionMap = mutableMapOf<String, Int>()
      if (sectionJson.has("mainIndex")) {
        sectionMap["mainIndex"] = sectionJson.getInt("mainIndex")
      }
      if (sectionJson.has("tutorialIndex")) {
        sectionMap["tutorialIndex"] = sectionJson.getInt("tutorialIndex")
      }
      progressMap[sectionName] = sectionMap
    }
    return progressMap
  }

  /**
   * Gets the user's prompt progress from the in-memory state.
   *
   * This function acquires the data lock and may block if another thread is holding the lock.
   *
   * @return A map representing the user's progress.
   */
  suspend fun getPromptProgress(): Map<String, Map<String, Int>> {
    dataManagerData.lock.withLock {
      return getPromptProgressUnderLock()
    }
  }

  /**
   * Gets the user's prompt progress from the in-memory state.
   *
   * This function **must** be called while holding the data lock.
   *
   * @return A map representing the user's progress.
   */
  private suspend fun getPromptProgressUnderLock(): Map<String, Map<String, Int>> {
    return dataManagerData.promptStateContainer?.promptProgress ?: emptyMap()
  }

  /**
   * Saves the user's prompt progress to the persistent `DataStore`.
   * The progress map is serialized into a JSON string before being saved.
   *
   * This function does not acquire the data lock. It performs a write to `DataStore` and
   * may block for a short time.
   *
   * @param progress The progress map to save.
   */
  private suspend fun savePromptProgress(progress: Map<String, Map<String, Int>>) {
    val keyObject = stringPreferencesKey("promptProgress")
    val json = JSONObject()
    progress.forEach { (sectionName, sectionMap) ->
      val sectionJson = JSONObject()
      sectionMap.forEach { (key, value) ->
        sectionJson.put(key, value)
      }
      json.put(sectionName, sectionJson)
    }
    context.prefStore.edit {
      it[keyObject] = json.toString()
    }
  }

  /**
   * Saves the current prompt index for the active section and tutorial mode.
   * This function updates the progress in both the in-memory state and the persistent
   * `DataStore`.
   *
   * This function acquires the data lock and may block if another thread is holding the lock.
   * It also performs a write to `DataStore`, which can block for a short time.
   *
   * @param index The prompt index to save.
   */
  suspend fun saveCurrentPromptIndex(index: Int) {
    dataManagerData.lock.withLock {
      saveCurrentPromptIndexUnderLock(index)
    }
  }

  /**
   * Saves the current prompt index for the active section and tutorial mode.
   * This function updates the progress in both the in-memory state and the persistent
   * `DataStore`.
   *
   * This function **must** be called while holding the data lock.
   * It performs a write to `DataStore`, which can block for a short time.
   *
   * @param index The prompt index to save.
   * @throws IllegalStateException if the prompt state or current section is not initialized.
   */
  private suspend fun saveCurrentPromptIndexUnderLock(index: Int) {
    val currentState = dataManagerData.promptStateContainer
      ?: throw IllegalStateException(
        "Attempted to save prompt index before prompt state was initialized."
      )
    val sectionName = currentState.currentSectionName
      ?: throw IllegalStateException(
        "Attempted to save prompt index without a current section set."
      )
    val newProgress = currentState.promptProgress.toMutableMap()
    val sectionProgress = newProgress[sectionName]?.toMutableMap() ?: mutableMapOf()

    if (currentState.tutorialMode) {
      sectionProgress["tutorialIndex"] = index
    } else {
      sectionProgress["mainIndex"] = index
    }
    newProgress[sectionName] = sectionProgress
    updatePromptStateAndPost(currentState.copy(promptProgress = newProgress))
    savePromptProgress(newProgress) // Persist to storage
  }

  /**
   * Sets the current section and resets the tutorial progress for that section if tutorial
   * mode is active.
   *
   * This function acquires the data lock and may block if another thread is holding the lock.
   *
   * @param sectionName The name of the section to switch to.
   */
  suspend fun resetToSection(sectionName: String) {
    dataManagerData.lock.withLock {
      setCurrentSectionUnderLock(sectionName)
      if (getTutorialModeUnderLock()) {
        saveCurrentPromptIndexUnderLock(0)
      }
    }
  }

  /**
   * Toggles the tutorial mode on or off.
   * If tutorial mode is enabled, it also resets the tutorial progress for the current section to 0.
   *
   * This function acquires the data lock and may block if another thread is holding the lock.
   */
  suspend fun toggleTutorialMode() {
    dataManagerData.lock.withLock {
      val tutorialMode = !getTutorialModeUnderLock()
      setTutorialModeUnderLock(tutorialMode)
      if (tutorialMode) {
        saveCurrentPromptIndexUnderLock(0)
      }
    }
  }

  /**
   * Generates a new, unique session ID.
   * The session ID is a combination of the device ID and an auto-incrementing session index,
   * which is persisted in `DataStore`.
   *
   * This function acquires the data lock to get the device ID. It also performs a read/write
   * operation on `DataStore` to update the session index. It may block.
   *
   * @return A unique session ID string (e.g., "user-abcdef12-s001").
   */
  suspend fun newSessionId(): String {
    dataManagerData.lock.withLock {
      val deviceId = getDeviceIdUnderLock()
      val username = getUsernameUnderLock()
      val keyObject = intPreferencesKey("sessionIdIndex")
      val oldValue = context.prefStore.edit { preferences ->
        preferences[keyObject] = (preferences[keyObject] ?: 0) + 1
      }
      val sessionIndex = oldValue[keyObject] ?: 0
      return "${username}-${deviceId}-s${padZeroes(sessionIndex, 3)}"
    }
  }

  private suspend fun reloadPromptsFromServerUnderLock(): Boolean {
    val currentState = dataManagerData.promptStateContainer
    if (currentState != null) {
      // Clear the fields in promptStateContainer.  Only relevant on some failures
      // as reinitializeDataUnderLock should set them from the permanent state on disk.
      dataManagerData.promptStateContainer = currentState.copy(
        promptsCollection = null,
        promptProgress = mutableMapOf<String, Map<String, Int>>(),
        currentSectionName = null
      )
    }
    context.prefStore.edit { preferences ->
      // Remove current prompt state.
      preferences.remove(stringPreferencesKey("currentSectionName"))
      preferences.remove(stringPreferencesKey("promptProgress"))
    }
    // TODO make ensureResources a part of this workflow rather than calling it every time the
    // prompts are loaded fom disk.
    val retVal = downloadPrompts()
    // Re-initialize the state from scratch after download (or failure).
    reinitializeDataUnderLock()
    return retVal
  }

  /**
   * Creates a new user account and initializes the local state.
   *
   * This function performs several actions:
   * 1.  Validates the username format.
   * 2.  Generates a new random password and creates a login token.
   * 3.  Sends a request to the server to register the new login token, authenticated by an
   *     admin password.
   * 4.  If registration is successful, it saves the new login token to a local file.
   * 5.  It then reloads the prompts from the server and re-initializes the local application
   *     state for the new user.
   *
   * This function acquires the data lock for its entire execution, which can be lengthy due
   * to network requests and file I/O. This will block other data operations.
   *
   * @param username The desired username. Must be at least 3 lowercase alphanumeric or
   * underscore characters, starting with a letter.
   * @param adminPassword The admin password required to authorize account creation on the server.
   * @return `true` if the account was created successfully, `false` otherwise.
   */
  suspend fun createAccount(username: String, adminPassword: String): Boolean {
    if (!Regex("^[a-z][a-z0-9_]{2,}$").matches(username)) {
      logToServer("createAccount username \"$username\" is invalid")
      Log.e(
        TAG,
        "username must be at least 3 lowercase alphanumeric or underscore " +
            "characters with the first character being a letter."
      )
      return false
    }
    dataManagerData.lock.withLock {
      logToServerUnderLock("createAccount for username \"$username\"")
      val password = toHex(SecureRandom().generateSeed(32))
      val newLoginToken = makeToken(username, password)
      val adminToken = makeToken("admin", adminPassword)
      val loginTokenPath = File(LOGIN_TOKEN_FULL_PATH)
      loginTokenPath.parentFile?.let { parent ->
        if (!parent.exists()) {
          logToServerUnderLock("creating directory for loginToken.")
          parent.mkdirs()
        }
      }

      val url = URL(getServer() + "/register_login")
      Log.d(TAG, "Registering login at $url")
      val (code, _) =
        serverFormPostRequest(
          url,
          mapOf(
            "app_version" to APP_VERSION,
            "admin_token" to adminToken,
            "login_token" to newLoginToken
          )
        )
      if (code < 200 || code >= 300) {
        return false
      }

      try {
        Log.i(TAG, "creating $LOGIN_TOKEN_FULL_PATH")
        FileOutputStream(LOGIN_TOKEN_FULL_PATH).use { stream ->
          stream.write(newLoginToken.toByteArray(Charsets.UTF_8))
        }
      } catch (e: IOException) {
        Log.e(TAG, "Failed to write login token to file", e)
        return false
      }

      dataManagerData.loginToken = newLoginToken
      reloadPromptsFromServerUnderLock()
      return true
    }
  }

  private suspend fun uploadState(): Boolean {
    if (UploadService.isPaused()) {
      throw InterruptedUploadException("tryUploadKeyValue interrupted.")
    }
    val json = JSONObject()
    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    json.put("timestamp", timestamp)
    json.put("username", getUsernameUnderLock())
    json.put("deviceId", getDeviceIdUnderLock())
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

    val registeredFiles = RegisteredFile.getAllRegisteredFiles(context)
    val filesJson = JSONArray().apply {
      for (registeredFile in registeredFiles) {
        val entry = registeredFile.toJson()
        entry.put("relativePath", registeredFile.relativePath)
        put(entry)
      }
    }
    json.put("registeredFiles", filesJson)

    val localFilesJson = JSONArray()
    json.put("localFiles", localFilesJson)
    context.filesDir.walk().forEach {
      if (it.isFile) {
        val entryJson = JSONObject()
        entryJson.put("path", it.path)
        localFilesJson.put(entryJson)
      }
    }

    json.put("prompts", getPromptsCollectionUnderLock()?.toJson())
    json.put("currentSectionName", getCurrentSectionNameUnderLock())
    json.put("promptProgress", JSONObject(getPromptProgressUnderLock()))
    val url = URL(getServer() + "/save_state")
    val (code, _) =
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
    for (i in 0..entries.length() - 1) {
      Log.i(TAG, "Uploading key: \"${entries.getJSONObject(i).getString("key")}\"")
    }
    val url = URL(getServer() + "/save")
    val (code, _) =
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

  /**
   * Creates a `Notification` for the upload foreground service.
   *
   * This is a helper function to construct a standardized notification for displaying
   * the status of the upload service.
   *
   * This function does not acquire the data lock and does not block.
   *
   * @param title The title of the notification.
   * @param message The body text of the notification.
   * @return A configured `Notification` object.
   */
  fun createNotification(title: String, message: String): Notification {
    return Notification.Builder(context, UPLOAD_NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(R.drawable.upload_service_notification_icon)
      .setContentTitle(title)
      .setContentText(message)
      .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
      .setTicker("$title: $message")
      .build()
  }

  /**
   * Records the current timestamp as the APK installation time.
   *
   * This function is typically called after a successful app update. It saves the current
   * timestamp to the `prefStore` `DataStore` and also logs the installation event to the
   * server via [logToServer].
   *
   * This function does not acquire the data lock. It performs a write to `DataStore` and
   * may block for a short time.
   *
   * @return `true` (the return value is not currently used meaningfully).
   */
  suspend fun updateApkTimestamp(): Boolean {
    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    val keyObject = stringPreferencesKey("apkInstallTimestamp")
    context.prefStore.edit { preferences ->
      preferences[keyObject] = timestamp
    }
    logToServer("apk installed at timestamp $timestamp")
    return true
  }

  /**
   * Checks with the server for a new application update.
   *
   * This function sends a request to the server, providing the timestamp of the currently
   * installed APK. The server can then determine if a newer version is available. The logic
   * for actually downloading and installing the update is handled by a server directive.
   *
   * This function does not acquire the data lock, but it performs a network request and
   * may block for a significant amount of time.
   *
   * @return `true` if the check was successful (even if no update is available), `false`
   * if the server request fails.
   */
  suspend fun updateApp(): Boolean {
    if (UploadService.isPaused()) {
      throw InterruptedUploadException("updateApp was interrupted.")
    }
    Log.i(TAG, "Check for latest apk.")
    val url = URL(getServer() + "/update_apk")
    val keyObject = stringPreferencesKey("apkInstallTimestamp")
    var timestamp = context.prefStore.data
      .map {
        it[keyObject]
      }.firstOrNull()
    if (timestamp == null) {
      timestamp = "null"
    }
    val (code, _) = serverFormPostRequest(
      url,
      mapOf(
        "app_version" to APP_VERSION,
        "login_token" to dataManagerData.loginToken!!,
        "timestamp" to timestamp!!
      )
    )
    if (code >= 200 && code < 300) {
      // Check JSON to see if we have the latest version.
    } else {
      Log.e(TAG, "unable download new apk.")
      return false
    }
    return true
  }

  /**
   * Manages the entire data upload process.
   *
   * This function orchestrates the upload of all persisted data to the server. It performs
   * the following steps in order:
   * 1.  Runs server directives by calling [runDirectives].
   * 2.  Uploads all staged key-value pairs from the `dataStore` in batches.
   * 3.  Uploads all registered files from the `registerFileStore`, providing progress updates
   *     via the `progressCallback`.
   *
   * This function acquires the data lock for its entire execution, which can be very lengthy
   * due to network requests and file I/O. This will block other data operations.
   *
   * @param notificationManager An optional `NotificationManager` to display upload progress.
   * @param progressCallback A function that is called with the upload progress percentage (0-100).
   * @return `true` if the entire upload process completes successfully, `false` otherwise.
   */
  suspend fun uploadData(
    notificationManager: NotificationManager? = null,
    progressCallback: (Int) -> Unit
  ): Boolean {
    if (!hasServer()) {
      Log.i(TAG, "Backend Server not specified.")
      return false
    }
    if (dataManagerData.loginToken == null) {
      Log.e(TAG, "No loginToken present, can not upload data.")
      return false
    }
    dataManagerData.lock.withLock {
      // First run directives, to make sure we have all the data we need.
      val directivesReturnValue = runDirectives()
      val entries = context.dataStore.data
        .map {
          it.asMap().entries
        }.firstOrNull()
      if (entries == null) {
        Log.i(TAG, "entries was null")
      } else if (entries.isNotEmpty()) {
        notificationManager?.notify(
          UPLOAD_NOTIFICATION_ID,
          createNotification("Uploading key/values", "${entries.size} key/values uploading")
        )
        var batch = JSONArray()
        var i = 0
        for (entry in entries.iterator()) {
          val jsonEntry = JSONObject(entry.value as String)
          batch.put(jsonEntry)
          i += 1
          // For production when the server is in Google Cloud API the batch size can be larger Maybe 500
          if (i >= 100) {
            // TODO Have some indication in the progress bar for each of these.
            if (!tryUploadKeyValues(batch)) {
              return false
            }
            i = 0
            batch = JSONArray()
          }
        }
        if (!tryUploadKeyValues(batch)) {
          return false
        }
      }
      val registeredFiles = RegisteredFile.getAllRegisteredFiles(context)
      var completedItems = 0
      var i = 0
      for (registeredFile in registeredFiles) {
        notificationManager?.notify(
          UPLOAD_NOTIFICATION_ID,
          createNotification("Uploading file", "${i + 1} of ${registeredFiles.size}")
        )

        Log.i(TAG, "Creating UploadSession for ${registeredFile.relativePath}")

        val uploadSession = UploadSession(context, registeredFile)
        if (!uploadSession.tryUploadFile()) {
          return false
        }

        completedItems += 1
        // TODO make the progress bar include the key value uploads.
        val progress = (completedItems * 100 / registeredFiles.size).coerceIn(0, 100)
        Log.d(TAG, "Progress after file upload: $progress%")
        progressCallback(progress)

        i += 1
      }

      return directivesReturnValue
    }
  }

  /**
   * A utility function that blocks until the data lock can be acquired and released.
   *
   * This function is used to synchronize operations by ensuring that any ongoing data
   * operations complete before proceeding. It acquires the lock and then immediately
   * releases it.
   *
   * This function will block if another thread is currently holding the data lock.
   */
  suspend fun waitForDataLock() {
    dataManagerData.lock.lock()
    dataManagerData.lock.unlock()
  }

  private fun directiveCompleted(id: String): Boolean {
    Log.i(TAG, "Marking directive completed.")
    val url = URL(getServer() + "/directive_completed")
    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    val (code, _) = serverFormPostRequest(
      url,
      mapOf(
        "app_version" to APP_VERSION,
        "login_token" to dataManagerData.loginToken!!,
        "id" to id, "timestamp" to timestamp
      )
    )
    if (code >= 200 && code < 300) {
    } else {
      Log.e(TAG, "unable to mark directive completed.")
      return false
    }
    return true
  }

  /**
   * Deletes the lifetime recording statistics from persistent storage.
   *
   * This function removes the keys for the lifetime recording count and duration from the
   * `prefStore` `DataStore`, effectively resetting them to zero.
   *
   * This function does not acquire the data lock. It performs a write to `DataStore` and
   * may block for a short time.
   */
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
      setTutorialModeUnderLock(true)
      directiveCompleted(id)  // Use the old loginToken.
      dataManagerData.loginToken = newLoginToken
      reloadPromptsFromServerUnderLock()
      return false  // Ignore further directives, the next round will be done with the new login.
    } else if (op == "resetStatistics") {
      resetStatistics()
      if (!directiveCompleted(id)) {
        return false
      }
    } else if (op == "setTutorialMode") {
      val json = JSONObject(value)
      val tutorialMode = json.getBoolean("tutorialMode")
      Log.i(TAG, "setTutorialMode to $tutorialMode")
      setTutorialModeUnderLock(tutorialMode)
      if (!directiveCompleted(id)) {
        return false
      }
    } else if (op == "updateApk") {
      val url = URL(getServer() + "/apk")
      Log.i(TAG, "updating apk to $url.")
      val filename = apkData.getString("md5") + ".apk"
      val relativePath = "apk" + File.separator + filename
      val filepath = File(context.filesDir, relativePath)

      filepath.parentFile?.let { parent ->
        if (!parent.exists()) {
          Log.i(TAG, "creating directory ${parent}.")
          parent.mkdirs()
        }
      }
      var downloadSucceeded = false
      try {
        FileOutputStream(filepath.absolutePath).use { fileOutputStream ->
          Log.i(TAG, "downloading apk to $filepath")
          downloadSucceeded = serverGetToFileRequest(url, emptyMap(), fileOutputStream)
        }
      } catch (e: IOException) {
        Log.e(TAG, "Failed to download apk", e)
      } finally {
        if (!downloadSucceeded) {
          Log.w(TAG, "Cleaning up failed download: $filepath")
          filepath.delete()
          return false
        }
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
    } else if (op == "setPrompts" || op == "reloadPrompts") {
      if (!reloadPromptsFromServerUnderLock()) {
        return false
      }
      if (!directiveCompleted(id)) {
        return false
      }
    } else if (op == "deleteFile") {
      val json = JSONObject(value)
      val relativePath = json.getString("filepath")
      RegisteredFile.deleteFile(context, relativePath)
      logToServerUnderLock("As directed: Deleted and unregistered file $relativePath")
      if (!directiveCompleted(id)) {
        return false
      }
    } else if (op == "deleteResources") {
      val resourceDir = File(context.filesDir, "resource")
      resourceDir.deleteRecursively()
      if (!directiveCompleted(id)) {
        return false
      }
    } else if (op == "uploadState") {
      if (uploadState()) {
        return directiveCompleted(id)
      } else {
        return false
      }
    } else {
      Log.e(TAG, "Unable to understand directive op \"$op\"")
      return false
    }
    return true
  }

  private suspend fun downloadPrompts(): Boolean {
    val url = URL(getServer() + "/prompts")
    Log.i(TAG, "downloading prompt data at $url.")
    val filepath = File(context.filesDir, PROMPTS_FILENAME)

    filepath.parentFile?.let { parentDir ->
      if (!parentDir.exists()) {
        Log.i(TAG, "creating directory $parentDir.")
        parentDir.mkdirs()
      }
    }
    Log.i(TAG, "deleting prompt data in $filepath yielded return value ${filepath.delete()}")
    Log.i(TAG, "downloading prompt data to $filepath")
    val (code, data) = serverFormPostRequest(
      url,
      mapOf(
        "app_version" to APP_VERSION,
        "login_token" to dataManagerData.loginToken!!,
      )
    )
    if (code >= 200 && code < 300) {
      if (data == null) {
        Log.e(TAG, "data was null.")
        return false
      }
      try {
        FileOutputStream(filepath).use {
          it.write(data.toByteArray(Charsets.UTF_8))
        }
      } catch (e: IOException) {
        Log.e(TAG, "Failed to write prompts to file", e)
        Log.i(TAG, "deleting prompt data in $filepath yielded return value ${filepath.delete()}")
        return false
      }
    } else {
      Log.e(TAG, "unable to fetch prompts.")
      Log.i(TAG, "deleting prompt data in $filepath yielded return value ${filepath.delete()}")
      return false
    }
    Log.i(TAG, "prompt data downloaded with size ${filepath.length()}")
    return true
  }

  /**
   * Initiates the installation of a downloaded APK file.
   *
   * This function uses the Android `PackageInstaller` API to create an installation session
   * and write the APK data to it. It then creates a `PendingIntent` that will be broadcast
   * when the installation is complete (or requires user action). Finally, it commits the
   * session, which prompts the user to approve the installation.
   *
   * This function does not acquire the data lock. It performs file I/O and interacts with
   * a system service, so it may block for a short time.
   *
   * @param relativePath The path to the APK file, relative to the app's files directory.
   * @param apkData A `JSONObject` containing metadata about the APK, such as its MD5 hash
   * and server timestamp. This data is passed through to the completion broadcast.
   */
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
    intent.action = ".upload.SESSION_API_PACKAGE_INSTALLED"
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

  /**
   * Fetches and executes a list of commands (directives) from the server.
   *
   * This function is a key part of the server-driven application management. It requests a
   * list of directives from the server and then executes them one by one. Directives can
   * include operations like changing users, updating the APK, reloading prompts, or deleting
   * files.
   *
   * This function does not acquire the data lock itself, but the directives it executes
   * (via [executeDirective]) may acquire the lock. It performs a network request and
   * potentially many other blocking operations depending on the directives received.
   *
   * @return `true` if all directives were fetched and executed successfully, `false` otherwise.
   */
  suspend fun runDirectives(): Boolean {
    if (!hasServer()) {
      Log.i(TAG, "Backend Server not specified.")
      return false
    }
    if (dataManagerData.loginToken == null) {
      Log.e(TAG, "No loginToken present, can not download data.")
      return false
    }
    if (UploadService.isPaused()) {
      throw InterruptedUploadException("runDirective was interrupted.")
    }

    Log.i(TAG, "Download the directives from server.")
    val url = URL(getServer() + "/directives")
    val (code, data) = serverFormPostRequest(
      url,
      mapOf(
        "app_version" to APP_VERSION,
        "login_token" to dataManagerData.loginToken!!
      )
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
    } else if (code >= 500) {
      Log.e(TAG, "unable to fetch directives due to server error (code ${code}).")
    } else {
      Log.e(TAG, "unable to fetch directives (code ${code}).")
      return false
    }
    return true
  }

  /**
   * Asynchronously stages a log message for upload to the server.
   *
   * This function creates a timestamped key and calls [addKeyValue] to stage the log
   * message. The actual database operation happens in a background coroutine launched
   * on the IO dispatcher.
   *
   * This function returns immediately and does not block.
   *
   * @param message The log message to save.
   */
  fun logToServer(message: String) {
    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    logToServerAtTimestamp(timestamp, message)
  }

  /**
   * Asynchronously stages a log message with a specific timestamp for upload to the server.
   *
   * This function calls [addKeyValue] to stage the log message with the provided timestamp.
   * The actual database operation happens in a background coroutine launched on the IO
   * dispatcher.
   *
   * This function returns immediately and does not block.
   *
   * @param timestamp The ISO 8601 timestamp for the log entry.
   * @param message The log message to save.
   */
  fun logToServerAtTimestamp(timestamp: String, message: String) {
    CoroutineScope(Dispatchers.IO).launch {
      addKeyValue("log-$timestamp", message, "log")
    }
  }

  suspend fun logToServerAndPersist(message: String) {
    dataManagerData.lock.withLock {
      logToServerUnderLock(message)
      persistDataUnderLock(false)
    }
  }

  private suspend fun logToServerUnderLock(message: String) {
    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    logToServerAtTimestampUnderLock(timestamp, message)
  }

  private suspend fun logToServerAtTimestampUnderLock(timestamp: String, message: String) {
    addKeyValue("log-$timestamp", message, "log", holdingLock = true)
  }

  /**
   * Saves a key-value pair to a staging area for eventual upload to the server.
   *
   * This function adds the given key, value, and partition to an in-memory map
   * (`dataManagerData.keyValues`). The data is not sent to the server immediately but is
   * held until [persistData] is called, which writes the staged data to persistent storage.
   * Re-using the same key will overwrite the existing value in the staging area.
   *
   * This function acquires the data lock and may block if another thread is holding the lock.
   *
   * @param key A unique identifier for the data.
   * @param value The string value to be saved.
   * @param partition A category or partition for the data on the server.
   * @param holdingLock If `true`, the function assumes the caller is already holding the data
   * lock and will not attempt to acquire it again. This is an optimization to avoid nested
   * lock acquisitions. Defaults to `false`.
   */
  suspend fun addKeyValue(
    key: String,
    value: String,
    partition: String,
    holdingLock: Boolean = false
  ) {
    Log.i(TAG, "Storing key: \"$key\", value: \"$value\".")
    val saveJson = JSONObject()
    saveJson.put("key", key)
    saveJson.put("message", value)
    saveJson.put("partition", partition)
    if (holdingLock) {
      dataManagerData.keyValues[key] = saveJson
    } else {
      dataManagerData.lock.withLock {
        dataManagerData.keyValues[key] = saveJson
      }
    }
  }

  /**
   * Saves a key-value pair, where the value is a `JSONObject`, to a staging area for
   * eventual upload to the server.
   *
   * This function is an overload of [addKeyValue] for JSON object values. It adds the given
   * key, JSON object, and partition to an in-memory map (`dataManagerData.keyValues`).
   * The data is not sent to the server immediately but is held until [persistData] is called.
   * Re-using the same key will overwrite the existing value in the staging area.
   *
   * This function acquires the data lock and may block if another thread is holding the lock.
   *
   * @param key A unique identifier for the data.
   * @param json The `JSONObject` to be saved.
   * @param partition A category or partition for the data on the server.
   * @param holdingLock If `true`, the function assumes the caller is already holding the data
   * lock and will not attempt to acquire it again. Defaults to `false`.
   */
  suspend fun addKeyValue(
    key: String,
    json: JSONObject,
    partition: String,
    holdingLock: Boolean = false
  ) {
    Log.i(TAG, "Storing key: \"$key\", json:\n${json.toString(2)}")
    val saveJson = JSONObject()
    saveJson.put("key", key)
    saveJson.put("data", json)
    saveJson.put("partition", partition)
    if (holdingLock) {
      dataManagerData.keyValues[key] = saveJson
    } else {
      dataManagerData.lock.withLock {
        dataManagerData.keyValues[key] = saveJson
      }
    }
  }

  /**
   * Registers a file to be uploaded to the server.
   *
   * This function adds the file's relative path to an in-memory map (`dataManagerData.registeredFiles`).
   * The file is not uploaded immediately but is staged until the upload process is initiated
   * (e.g., by [uploadData]). The actual upload logic is handled by the `UploadSession` class.
   *
   * This function acquires the data lock and may block if another thread is holding the lock.
   *
   * @param relativePath The path of the file relative to the application's files directory.
   */
  suspend fun registerFile(relativePath: String, tutorialMode: Boolean) {
    RegisteredFile.registerFile(context, relativePath, tutorialMode)
  }

  /**
   * Persists all staged key-value pairs and registered files to local storage.
   *
   * This function takes all the data that has been staged in the in-memory maps
   * (`dataManagerData.keyValues` and `dataManagerData.registeredFiles`) and writes it to
   * the appropriate `DataStore`. After the data is persisted, the in-memory maps are cleared.
   * This is a critical step to ensure that data is not lost if the application is closed
   * before the upload is complete.
   *
   * This function acquires the data lock for the entire duration of the persistence process.
   * As a result, any other calls to functions that require the lock (like [addKeyValue] and
   * [registerFile]) will block until this function completes. It performs file I/O and may
   * block for a significant amount of time.
   */
  suspend fun persistData(verbose: Boolean = true) {
    dataManagerData.lock.withLock {
      persistDataUnderLock(verbose = verbose)
    }
  }

  private suspend fun persistDataUnderLock(verbose: Boolean = true) {
    val tutorialMode = getTutorialModeUnderLock()
    if (verbose) {
      Log.i(TAG, "Starting to persist data")
    }
    context.dataStore.edit { preferences ->
      dataManagerData.keyValues.forEach {
        val keyObject = stringPreferencesKey(it.key)
        if (tutorialMode) {
          it.value.put("tutorialMode", tutorialMode)
        }
        preferences[keyObject] = it.value.toString()
      }
    }
    dataManagerData.keyValues.clear()
    if (verbose) {
      Log.i(TAG, "Finished persisting data.")
    }
  }

  /**
   * Gets the current collection of prompts from the in-memory state.
   *
   * This function provides access to the `PromptsCollection` object, which contains all the
   * sections and prompts currently loaded by the application.
   *
   * This function acquires the data lock and may block if another thread is holding the lock.
   *
   * @return The current `PromptsCollection`, or `null` if it has not been loaded yet.
   */
  suspend fun getPromptsCollection(): PromptsCollection? {
    dataManagerData.lock.withLock {
      return getPromptsCollectionUnderLock()
    }
  }

  /**
   * Gets the current collection of prompts from the in-memory state.
   *
   * This function **must** be called while holding the data lock.
   *
   * @return The current `PromptsCollection`, or `null` if it has not been loaded yet.
   */
  private suspend fun getPromptsCollectionUnderLock(): PromptsCollection? {
    return dataManagerData.promptStateContainer?.promptsCollection
  }

  /**
   * Reads the prompts collection from the local filesystem.
   *
   * This function initializes a `PromptsCollection` object from the `prompts.json` file
   * stored in the application's local data directory. It also calls [ensureResources] to
   * trigger the download of any images or other resources required by the prompts.
   *
   * This function does not acquire the data lock, but it performs file I/O and network
   * requests, and may block for a significant amount of time.
   *
   * @return The loaded `PromptsCollection`, or `null` if the prompts file cannot be
   * read or if a required resource cannot be downloaded.
   */
  private suspend fun getPromptsCollectionFromDisk(): PromptsCollection? {
    val newPromptsCollection = PromptsCollection(context)
    if (!newPromptsCollection.initialize()) {
      return null
    }
    if (!ensureResources(newPromptsCollection)) {
      return null
    }
    return newPromptsCollection
  }

  /**
   * Ensures that all resources required by a `PromptsCollection` are available locally.
   *
   * This function iterates through all sections and prompts within the given collection
   * and calls [ensureResources] on each set of prompts to download any missing resources.
   *
   * This function does not acquire the data lock, but it can trigger network requests via
   * [ensureResource] and may block for a significant amount of time.
   *
   * @param promptsCollection The collection of prompts to check.
   * @return `true` if all resources are successfully downloaded, `false` otherwise.
   */
  suspend fun ensureResources(promptsCollection: PromptsCollection): Boolean {
    Log.i(TAG, "ensureResources for promptsCollection")
    for (section in promptsCollection.sections.values) {
      Log.i(TAG, "processing ensureResources for section ${section.name}")
      if (!ensureResources(section.mainPrompts)) {
        return false
      }
      if (!ensureResources(section.tutorialPrompts)) {
        return false
      }
    }
    return true
  }

  /**
   * Ensures that all resources required by a list of prompts are available locally.
   *
   * This function iterates through the given `Prompts` object and calls [ensureResource]
   * for each prompt that has an associated `resourcePath`.
   *
   * This function does not acquire the data lock, but it can trigger network requests via
   * [ensureResource] and may block for a significant amount of time.
   *
   * @param prompts The `Prompts` object containing the list of prompts to check.
   * @return `true` if all resources are successfully downloaded, `false` otherwise.
   */
  suspend fun ensureResources(prompts: Prompts): Boolean {
    for (prompt in prompts.array) {
      // Log.d(TAG, "prompt ${prompt.toJson()}")
      if (prompt.resourcePath != null) {
        if (!ensureResource(prompt.resourcePath)) {
          Log.e(TAG, "failed to acquire resource ${prompt.resourcePath}")
          return false
        }
      }
    }
    return true
  }

  /**
   * Ensures that a specific resource is available locally, downloading it if necessary.
   *
   * This function checks if a file exists at the given `resourcePath` within the app's
   * local file storage. If the file does not exist, it calls [downloadResource] to fetch
   * it from the server. It also enforces that all resource paths must start with "resource/".
   *
   * This function does not acquire the data lock, but it may perform a network request
   * and can block for a significant amount of time if the resource needs to be downloaded.
   *
   * @param resourcePath The server path of the resource (e.g., "resource/image.png").
   * @return `true` if the resource exists locally or was downloaded successfully, `false` otherwise.
   */
  suspend fun ensureResource(resourcePath: String): Boolean {
    Log.d(TAG, "ensureResource: resourcePath ${resourcePath}")
    // Explicitly enforce that all resources are in resource/ in GCP bucket
    if (!resourcePath.startsWith("resource/")) {
      Log.e(TAG, "resource path does not start with \"resource/\" ${resourcePath}")
      return false
    }
    val resource = File(context.filesDir, resourcePath)
    if (resource.exists()) {
      Log.d(TAG, "ensureResource: resource already exists ${resource}")
      return true
    }
    return downloadResource(resourcePath)
  }

  /**
   * Downloads a resource file from the server.
   *
   * This function constructs the full URL for the resource, creates the necessary local
   * directories, and then uses [serverGetToFileRequest] to download the file and save it
   * to the specified `resourcePath`. If the download fails, it cleans up by deleting the
   * partially downloaded file.
   *
   * This function does not acquire the data lock, but it performs network and file I/O
   * and will block for a significant amount of time.
   *
   * @param resourcePath The server path of the resource to download.
   * @return `true` if the download was successful, `false` otherwise.
   */
  suspend fun downloadResource(resourcePath: String): Boolean {
    val resource = File(context.filesDir, resourcePath)
    resource.parentFile?.let { parent ->
      if (!parent.exists()) {
        Log.i(TAG, "creating directory for resource: $parent")
        parent.mkdirs()
      }
    }

    val data = mapOf(
      "app_version" to APP_VERSION,
      "login_token" to dataManagerData.loginToken!!,
      "path" to resourcePath,
    )
    val formData = data.map { (k, v) ->
      URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
    }.joinToString("&")
    // TODO make sure this doesn't get logged anywhere.
    // I'd be more comfortable with this if we used the POST method.
    val url = URL(getServer() + "/resource?" + formData)

    var downloadSucceeded = false
    try {
      FileOutputStream(resource.absolutePath).use { fileOutputStream ->
        Log.i(TAG, "downloading resource (from url: $url) to $resource")
        downloadSucceeded = serverGetToFileRequest(url, emptyMap(), fileOutputStream)
      }
    } catch (e: IOException) {
      Log.e(TAG, "Failed to download resource", e)
    } finally {
      if (!downloadSucceeded) {
        Log.w(TAG, "Cleaning up failed resource download: $resource")
        resource.delete()
        return false
      }
    }

    Log.i(TAG, "downloaded resource $resource with size ${resource.length()}")
    return true
  }

  suspend fun saveClipData(clipDetails: ClipDetails) {
    val json = clipDetails.toJson()
    // Use a consistent key based on the clipId so that any changes to the clip
    // will be updated on the server.
    addKeyValue("clipData-${clipDetails.clipId}", json, "clip")
  }

  /**
   * Updates the lifetime recording statistics for the user.
   *
   * This function increments the total number of recordings and adds the duration of the
   * last session to the total recording time. These statistics are stored persistently
   * in the `prefStore` `DataStore`.
   *
   * This function does not acquire the data lock. It performs a write to `DataStore` and
   * may block for a short time.
   *
   * @param sessionLength The `Duration` of the recording session to add to the total.
   */
  suspend fun updateLifetimeStatistics(sessionLength: Duration) {
    val recordingCountKeyObject = intPreferencesKey("lifetimeRecordingCount")
    val recordingMsKeyObject = longPreferencesKey("lifetimeRecordingMs")
    context.prefStore.edit { preferences ->
      preferences[recordingCountKeyObject] = (preferences[recordingCountKeyObject] ?: 0) + 1
      preferences[recordingMsKeyObject] =
        (preferences[recordingMsKeyObject] ?: 0) + sessionLength.toMillis()
    }
  }

  suspend fun saveSessionInfo(sessionInfo: RecordingSessionInfo) {
    val json = sessionInfo.toJson()
    // Use a consistent key so that any changes will be updated on the server.
    addKeyValue("sessionData-${sessionInfo.sessionId}", json, "session")
  }

  private suspend fun saveSessionInfoUnderLock(sessionInfo: RecordingSessionInfo) {
    val json = sessionInfo.toJson()
    // Use a consistent key so that any changes will be updated on the server.
    addKeyValue("sessionData-${sessionInfo.sessionId}", json, "session", holdingLock = true)
  }

  fun launchSaveRecordingData(
    filename: String,
    outputFile: File,
    tutorialMode: Boolean,
    sessionInfo: RecordingSessionInfo,
    currentPromptIndex: Int
  ) {
    CoroutineScope(Dispatchers.IO).launch {
      dataManagerData.lock.withLock {
        saveRecordingDataUnderLock(
          filename,
          outputFile,
          tutorialMode,
          sessionInfo,
          currentPromptIndex
        )
      }
    }
  }

  private suspend fun saveRecordingDataUnderLock(
    filename: String,
    outputFile: File,
    tutorialMode: Boolean,
    sessionInfo: RecordingSessionInfo,
    currentPromptIndex: Int
  ) {
    val now = Instant.now()
    val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
    val json = JSONObject()
    json.put("filename", filename)
    json.put("endTimestamp", timestamp)
    addKeyValue("recording_stopped-${timestamp}", json, "recording", holdingLock = true)
    registerFile(
      outputFile.relativeTo(context.filesDir).path,
      tutorialMode
    )
    sessionInfo.endTimestamp = now
    sessionInfo.finalPromptIndex = currentPromptIndex
    saveCurrentPromptIndexUnderLock(currentPromptIndex)
    saveSessionInfoUnderLock(sessionInfo)
    updateLifetimeStatistics(
      Duration.between(sessionInfo.startTimestamp, sessionInfo.endTimestamp)
    )
    persistDataUnderLock()
    Log.i(TAG, "Saved all session data to disk.")
  }

  /**
   * Initiates a check of the server connection status.
   *
   * This function launches a coroutine on the IO dispatcher to ping the server by calling
   * [pingServer]. The result of the ping is then posted to the `_serverStatus` `LiveData`,
   * allowing the UI to observe changes in server connectivity.
   *
   * This function returns immediately and does not block. The network request happens
   * asynchronously in the background.
   */
  fun checkServerConnection() {
    CoroutineScope(Dispatchers.IO).launch {
      pingServer()
    }
  }

  /**
   * Updates the in-memory state container and posts the new state to the `LiveData`.
   *
   * This is a central helper function for managing the application's state. It updates the
   * `promptStateContainer` with the new state and then calls `postValue` on the `_promptState`
   * `MutableLiveData` to notify all observers (typically the UI) of the change.
   *
   * This function should be called from within a data lock to ensure thread safety.
   *
   * @param newState The new `PromptState` to set as the current state.
   */
  private fun updatePromptStateAndPost(newState: PromptState) {
    dataManagerData.promptStateContainer = newState
    dataManagerData._promptState.postValue(newState)
  }

  /**
   * Pings the server to check for connectivity.
   *
   * This function attempts to open a connection to the server's base URL with a short
   * timeout. It updates the `_serverStatus` `LiveData` with the result of the connection
   * attempt.
   *
   * This function does not acquire the data lock, but it performs a network request and
   * will block for the duration of the timeout (or until a connection is established/fails).
   *
   * @return `true` if the server is reachable (responds with HTTP 200 OK), `false` otherwise.
   */
  private fun pingServer(): Boolean {
    try {
      val url = URL(getServer())
      val urlConnection = url.openConnection() as HttpsURLConnection
      setAppropriateTrust(urlConnection)
      urlConnection.requestMethod = "GET"
      urlConnection.connectTimeout = 5000
      urlConnection.readTimeout = 5000
      val responseCode = urlConnection.responseCode
      val success = responseCode == HttpURLConnection.HTTP_OK
      dataManagerData._serverStatus.postValue(success)
      return success
    } catch (e: Exception) {
      Log.d(TAG, e.toString())
      dataManagerData._serverStatus.postValue(false)
      return false
    }
  }
}
