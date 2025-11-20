package edu.gatech.ccg.recordthesehands.upload

import android.content.Context
import android.os.Parcelable
import android.util.Log
import edu.gatech.ccg.recordthesehands.Constants
import kotlinx.parcelize.Parcelize
import org.json.JSONException
import org.json.JSONObject
import java.io.File

data class PromptsCollection(
  val sections: Map<String, PromptsSection>,
  val collectionMetadata: PromptsCollectionMetadata,
) {

  companion object {
    private val TAG = PromptsCollection::class.simpleName

    fun fromPromptsFile(context: Context): PromptsCollection? {
      val promptsFile = File(context.filesDir, Constants.PROMPTS_FILENAME)
      if (!promptsFile.exists()) return null

      val json = try {
        JSONObject(promptsFile.readText())
      } catch (e: JSONException) {
        Log.e(TAG, "Failed to parse prompts file: $e")
        return null
      }
      return fromJson(json)
    }

    fun fromJson(json: JSONObject): PromptsCollection? {
      val collectionMetadataJson = json.optJSONObject("metadata") ?: JSONObject()
      val instructions = collectionMetadataJson.opt("instructions")?.let {
        InstructionsData.fromJson(it as JSONObject)
      }
      val collectionMetadata = PromptsCollectionMetadata(
        defaultSection = collectionMetadataJson.opt("defaultSection") as? String,
        instructions = instructions,
      )
      val sections = mutableMapOf<String, PromptsSection>()
      val data = json.optJSONObject("data") ?: run {
        Log.e(TAG, "Prompts file did not include \"data\" section")
        return null
      }
      data.keys().forEach { sectionName ->
        val sectionJson = data.getJSONObject(sectionName)
        val metadataJson = sectionJson.optJSONObject("metadata") ?: JSONObject()
        // Create metadata object
        val metadata = PromptsSectionMetadata.fromJson(metadataJson)

        // Create and populate Prompts objects
        val mainPrompts = Prompts().apply {
          populateFrom(sectionJson.getJSONArray("main"))
        }
        val tutorialPrompts = Prompts().apply {
          // Check if a dedicated tutorial prompt set exists.
          val tutorialJsonArray = sectionJson.optJSONArray("tutorial")
          if (tutorialJsonArray != null) {
            // If it exists, use it.
            populateFrom(tutorialJsonArray)
          } else {
            // Otherwise, use the first 5 prompts from the main set as the tutorial.
            val mainJsonArray = sectionJson.getJSONArray("main")
            val newTutorialArray = org.json.JSONArray()
            for (i in 0 until minOf(5, mainJsonArray.length())) {
              newTutorialArray.put(mainJsonArray.getJSONObject(i))
            }
            populateFrom(newTutorialArray)
          }
        }

        sections[sectionName] = PromptsSection(sectionName, metadata, mainPrompts, tutorialPrompts)
      }
      return PromptsCollection(sections, collectionMetadata)
    }
  }

  fun toJson(): JSONObject {
    val json = JSONObject()
    val sectionsJson = JSONObject()
    for ((key, value) in sections) {
      sectionsJson.put(key, value.toJson())
    }
    json.put("sections", sectionsJson)
    json.put("metadata", collectionMetadata.toJson())
    return json
  }
}

data class PromptsCollectionMetadata(
  val defaultSection: String?,
  val instructions: InstructionsData?,
) {
  fun toJson(): JSONObject {
    val json = JSONObject()
    if (defaultSection != null) {
      json.put("defaultSection", defaultSection)
    }
    if (instructions != null) {
      json.put("instructions", instructions.toJson())
    }
    return json
  }
}

data class PromptsSectionMetadata(
  val dataCollectionId: String?,
  val instructions: InstructionsData?,
) {

  companion object {
    fun fromJson(json: JSONObject): PromptsSectionMetadata {
      val instructions = json.opt("instructions")?.let {
        InstructionsData.fromJson(it as JSONObject)
      }
      return PromptsSectionMetadata(
        dataCollectionId = json.opt("dataCollectionId") as? String,
        instructions = instructions,
      )
    }
  }

  fun toJson(): JSONObject {
    val json = JSONObject()
    json.put("dataCollectionId", dataCollectionId)
    if (instructions != null) {
      json.put("instructions", instructions.toJson())
    }
    return json
  }
}

@Parcelize
data class InstructionsData(
  val instructionsText: String?,
  val instructionsVideo: String?,
  val examplePrompt: Prompt?,
) : Parcelable {

  companion object {
    fun fromJson(json: JSONObject): InstructionsData {
      val examplePrompt = json.opt("examplePrompt")?.let {
        Prompt.fromJson(0, it as JSONObject)
      }
      return InstructionsData(
        instructionsText = json.opt("instructionsText") as? String,
        instructionsVideo = json.opt("instructionsVideo") as? String,
        examplePrompt = examplePrompt,
      )
    }
  }

  fun toJson(): JSONObject {
    val json = JSONObject()
    if (instructionsText != null) {
      json.put("instructionsText", instructionsText)
    }
    if (instructionsVideo != null) {
      json.put("instructionsVideo", instructionsVideo)
    }
    if (examplePrompt != null) {
      json.put("examplePrompt", examplePrompt)
    }
    return json
  }
}

class PromptsSection(
  val name: String,
  val metadata: PromptsSectionMetadata,
  val mainPrompts: Prompts,
  val tutorialPrompts: Prompts
) {
  fun toJson(): JSONObject {
    val json = JSONObject()
    json.put("name", name)
    json.put("metadata", metadata.toJson())
    json.put("mainPrompts", mainPrompts.toJson())
    json.put("tutorialPrompts", tutorialPrompts.toJson())
    return json
  }
}
