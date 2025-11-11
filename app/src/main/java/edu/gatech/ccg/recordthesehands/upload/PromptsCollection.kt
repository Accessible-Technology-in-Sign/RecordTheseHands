package edu.gatech.ccg.recordthesehands.upload

import android.content.Context
import edu.gatech.ccg.recordthesehands.Constants
import org.json.JSONObject
import java.io.File

class PromptsCollection(val context: Context) {
  var sections = mutableMapOf<String, PromptsSection>()

  suspend fun initialize(): Boolean {
    val promptsFile = File(context.filesDir, Constants.PROMPTS_FILENAME)
    if (!promptsFile.exists()) return false

    val json = JSONObject(promptsFile.readText())
    val data = json.getJSONObject("data")
    data.keys().forEach { sectionName ->
      val sectionJson = data.getJSONObject(sectionName)
      val metadataJson = sectionJson.optJSONObject("metadata") ?: JSONObject()

      // Create metadata object
      val metadata = PromptsSectionMetadata(
        dataCollectionId = metadataJson.opt("dataCollectionId") as? String,
        instructionsText = metadataJson.opt("instructionsText") as? String,
        instructionsVideo = metadataJson.opt("instructionsVideo") as? String,
      )

      // Create and populate Prompts objects
      val mainPrompts = Prompts(context).apply {
        populateFrom(sectionJson.getJSONArray("main"))
      }
      val tutorialPrompts = Prompts(context).apply {
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
    return true
  }

  fun toJson(): JSONObject {
    val json = JSONObject()
    val sectionsJson = JSONObject()
    for ((key, value) in sections) {
      sectionsJson.put(key, value.toJson())
    }
    json.put("sections", sectionsJson)
    return json
  }
}

data class PromptsSectionMetadata(
  val dataCollectionId: String?,
  val instructionsText: String?,
  val instructionsVideo: String?,
) {
  fun toJson(): JSONObject {
    val json = JSONObject()
    json.put("dataCollectionId", dataCollectionId)
    if (instructionsText != null) {
      json.put("instructionsText", instructionsText)
    }
    if (instructionsVideo != null) {
      json.put("instructionsVideo", instructionsVideo)
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
