package edu.gatech.ccg.recordthesehands.upload

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class PromptType {
  TEXT, IMAGE, VIDEO
}

class Prompt(
  val index: Int,
  val key: String,
  val type: PromptType,
  val prompt: String?,
  val resourcePath: String?
) {
  fun toJson(): JSONObject {
    val json = JSONObject()
    json.put("index", index)
    json.put("key", key)
    json.put("type", type.toString())
    if (prompt != null) {
      json.put("prompt", prompt)
    }
    if (resourcePath != null) {
      json.put("resourcePath", resourcePath)
    }
    return json
  }

}

/**
 * A class which encapsulates the raw prompts data obtained from the server.
 * No mutable state such as which index is being used is contained within this class.
 */
class Prompts(val context: Context) {
  companion object {
    private val TAG = Prompts::class.simpleName
  }

  var array = ArrayList<Prompt>()

  fun populateFrom(promptsJsonArray: JSONArray) {
    array.clear()
    try {
      array.ensureCapacity(promptsJsonArray.length())
      for (i in 0 until promptsJsonArray.length()) {
        val data = promptsJsonArray.getJSONObject(i)
        val key = if (data.has("promptId")) {
          data.getString("promptId")
        } else {
          data.getString("key")
        }
        var promptType = PromptType.TEXT
        if (data.has("type")) {
          promptType = PromptType.valueOf(data.getString("type"))
        }
        var prompt: String? = null
        if (data.has("prompt")) {
          prompt = data.getString("prompt")
        }
        var resourcePath: String? = null
        if (data.has("resourcePath")) {
          resourcePath = data.getString("resourcePath")
        }
        array.add(Prompt(i, key, promptType, prompt, resourcePath))
      }
    } catch (e: JSONException) {
      Log.e(TAG, "failed to parse prompts, encountered JSONException $e")
    }
  }

  fun toJson(): JSONObject {
    val json = JSONObject()
    val arrayJson = JSONArray()
    for (prompt in array) {
      arrayJson.put(prompt.toJson())
    }
    json.put("prompts", arrayJson)
    return json
  }
}
