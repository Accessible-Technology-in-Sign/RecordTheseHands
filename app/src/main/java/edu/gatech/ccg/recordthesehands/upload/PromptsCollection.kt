package edu.gatech.ccg.recordthesehands.upload

import android.content.Context
import edu.gatech.ccg.recordthesehands.Constants
import org.json.JSONObject
import java.io.File

class PromptsCollection(val context: Context) {
  var sections = mutableMapOf<String, PromptSection>()

  suspend fun initialize(): Boolean {
    val promptsFile = File(context.filesDir, Constants.PROMPTS_FILENAME)
    if (!promptsFile.exists()) return false

    val json = JSONObject(promptsFile.readText())
    val data = json.getJSONObject("data")
    data.keys().forEach { sectionName ->
      val sectionJson = data.getJSONObject(sectionName)
      val metadataJson = sectionJson.optJSONObject("metadata") ?: JSONObject()

      // Create metadata object
      val metadata = PromptSectionMetadata(
        dataCollectionId = metadataJson.opt("dataCollectionId") as? String,
        useSummaryPage = metadataJson.optBoolean("useSummaryPage", false)
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

      sections[sectionName] = PromptSection(sectionName, metadata, mainPrompts, tutorialPrompts)
    }
    return true
  }
}

data class PromptSectionMetadata(
  val dataCollectionId: String?,
  val useSummaryPage: Boolean
)

class PromptSection(
  val name: String,
  val metadata: PromptSectionMetadata,
  val mainPrompts: Prompts,
  val tutorialPrompts: Prompts
)
