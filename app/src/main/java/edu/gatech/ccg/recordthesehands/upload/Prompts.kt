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
  val promptId: String,
  val type: PromptType,
  val prompt: String?,
  val resourcePath: String?,
  val readMinMs: Int?,
  val recordMinMs: Int?
) {
  fun toJson(): JSONObject {
    val json = JSONObject()
    json.put("index", index)
    json.put("promptId", promptId)
    json.put("type", type.toString())
    if (prompt != null) {
      json.put("prompt", prompt)
    }
    if (resourcePath != null) {
      json.put("resourcePath", resourcePath)
    }
    if (readMinMs != null) {
      json.put("readMinMs", readMinMs)
    }
    if (recordMinMs != null) {
      json.put("recordMinMs", recordMinMs)
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
        val promptId: String = if (data.has("promptId")) {
          data.getString("promptId")
        } else {
          data.opt("key") as? String ?: throw IllegalStateException("promptId not specified")
        }
        var promptType = PromptType.TEXT
        if (data.has("promptType")) {
          promptType = PromptType.valueOf(data.getString("promptType").uppercase())
        }
        val prompt: String? = data.opt("prompt") as? String
        val resourcePath: String? = data.opt("resourcePath") as? String
        val readMinMs: Int? = data.opt("readMinMs") as? Int
        val recordMinMs: Int? = data.opt("recordMinMs") as? Int
        array.add(
          Prompt(
            i,
            promptId,
            promptType,
            prompt,
            resourcePath,
            readMinMs,
            recordMinMs
          )
        )
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
