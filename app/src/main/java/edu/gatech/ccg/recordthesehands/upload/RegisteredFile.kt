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

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.firstOrNull
import org.json.JSONObject
import java.io.File

private val Context.registerFileStore: DataStore<Preferences> by preferencesDataStore(
  name = "registerFileStore"
)

class RegisteredFile(
  private val context: Context,
  val relativePath: String
) {

  companion object {
    private val TAG = RegisteredFile::class.simpleName

    suspend fun getAllRegisteredFiles(context: Context): List<RegisteredFile> {
      val files = mutableListOf<RegisteredFile>()
      val preferences = context.registerFileStore.data.firstOrNull() ?: return emptyList()

      for (entry in preferences.asMap()) {
        val relativePath = entry.key.name
        val fileState = entry.value as String
        val registeredFile = RegisteredFile(context, relativePath)
        registeredFile.loadState(fileState)
        files.add(registeredFile)
      }
      return files
    }

    suspend fun registerFile(context: Context, relativePath: String, tutorialMode: Boolean) {
      Log.i(TAG, "Registering file for upload: \"$relativePath\"")
      val keyObject = stringPreferencesKey(relativePath)
      context.registerFileStore.edit { preferences ->
        preferences[keyObject] = JSONObject().apply {
           put("tutorialMode", tutorialMode)
        }.toString()
      }
    }

    suspend fun deleteFile(context: Context, relativePath: String) {
      Log.i(TAG, "Deleting file and registration for: \"$relativePath\"")
      // 1. Delete the physical file
      val file = File(context.filesDir, relativePath)
      if (file.exists()) {
        if (file.delete()) {
          Log.i(TAG, "Deleted physical file: $relativePath")
        } else {
          Log.e(TAG, "Failed to delete physical file: $relativePath")
        }
      }

      // 2. Delete the registration entry from DataStore
      val keyObject = stringPreferencesKey(relativePath)
      context.registerFileStore.edit { preferences ->
        preferences.remove(keyObject)
      }
    }
  }

  var fileSize: Long? = null
  var md5sum: String? = null
  var uploadLink: String? = null
  var sessionLink: String? = null
  var uploadCompleted: Boolean = false
  var uploadVerified: Boolean = false
  var tutorialMode: Boolean = false

  fun loadState(registerStoreValue: String) {
    Log.i(TAG, "Loading current session state $registerStoreValue")
    fileSize = null
    md5sum = null
    uploadLink = null
    sessionLink = null
    uploadCompleted = false
    uploadVerified = false
    tutorialMode = false

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
    if (inputJson.has("tutorialMode")) {
      tutorialMode = inputJson.getBoolean("tutorialMode")
    }
  }

  fun toJson(): JSONObject {
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
    if (tutorialMode) {
      json.put("tutorialMode", tutorialMode)
    }
    return json
  }

  suspend fun saveState() {
    Log.i(TAG, "Saving current session state")
    val keyObject = stringPreferencesKey(relativePath)
    context.registerFileStore.edit { preferences ->
      preferences[keyObject] = toJson().toString()
    }
  }

  suspend fun resetState() {
    fileSize = null
    md5sum = null
    uploadLink = null
    sessionLink = null
    uploadCompleted = false
    uploadVerified = false
    tutorialMode = false

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
    tutorialMode = false

    val keyObject = stringPreferencesKey(relativePath)
    context.registerFileStore.edit { preferences ->
      preferences.remove(keyObject)
    }
  }
}
