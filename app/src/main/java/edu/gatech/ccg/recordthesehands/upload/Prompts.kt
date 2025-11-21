package edu.gatech.ccg.recordthesehands.upload

import android.os.Parcelable
import android.util.Log
import kotlinx.parcelize.Parcelize
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

@Parcelize
enum class PromptType : Parcelable {
  TEXT, IMAGE, VIDEO
}

@Parcelize
class Prompt(
  val index: Int,
  val promptId: String,
  val promptType: PromptType,
  val prompt: String?,
  val resourcePath: String?,
  val readMinMs: Int?,
  val recordMinMs: Int?
) : Parcelable {

  companion object {
    fun fromJson(index: Int, json: JSONObject): Prompt {
      val promptId: String = if (json.has("promptId")) {
        json.getString("promptId")
      } else {
        json.opt("key") as? String ?: throw IllegalStateException("promptId not specified")
      }
      var promptType = PromptType.TEXT
      if (json.has("promptType")) {
        try {
          promptType = PromptType.valueOf(json.getString("promptType").uppercase())
        } catch (e: IllegalArgumentException) {
          Log.e(
            "Prompt",
            "Unknown prompt type: ${json.getString("promptType")}, defaulting to TEXT"
          )
          promptType = PromptType.TEXT
        }
      }
      val prompt: String? = json.opt("prompt") as? String
      val resourcePath: String? = json.opt("resourcePath") as? String
      val readMinMs: Int? = json.opt("readMinMs") as? Int
      val recordMinMs: Int? = json.opt("recordMinMs") as? Int
      return Prompt(
        index,
        promptId,
        promptType,
        prompt,
        resourcePath,
        readMinMs,
        recordMinMs
      )
    }
  }

  fun toJson(): JSONObject {
    val json = JSONObject()
    json.put("index", index)
    json.put("promptId", promptId)
    json.put("type", promptType.toString())
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
class Prompts() {
  companion object {
    private val TAG = Prompts::class.simpleName
  }

  var array = ArrayList<Prompt>()

  fun populateFrom(promptsJsonArray: JSONArray) {
    array.clear()
    try {
      array.ensureCapacity(promptsJsonArray.length())
      for (i in 0 until promptsJsonArray.length()) {
        array.add(Prompt.fromJson(i, promptsJsonArray.getJSONObject(i)))
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
