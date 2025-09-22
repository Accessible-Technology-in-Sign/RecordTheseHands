package edu.gatech.ccg.recordthesehands.upload

data class PromptState(
  // Core State
  val tutorialMode: Boolean,
  val promptsCollection: PromptsCollection?,
  val promptProgress: Map<String, Map<String, Int>>,
  val currentSectionName: String?,
  val username: String?,
  val deviceId: String?,

  ) {
  // Derived State (for UI convenience)
  val currentPrompts: Prompts?
    get() {
      val section = promptsCollection?.sections?.get(currentSectionName)
      return if (section != null) {
        if (tutorialMode) section.tutorialPrompts else section.mainPrompts
      } else {
        null
      }
    }

  val currentPromptIndex: Int?
    get() {
      return if (currentSectionName != null) {
        val sectionProgress = promptProgress[currentSectionName]
        if (tutorialMode) {
          sectionProgress?.get("tutorialIndex") ?: 0
        } else {
          sectionProgress?.get("mainIndex") ?: 0
        }
      } else {
        null
      }
    }

  val totalPromptsInCurrentSection: Int?
    get() = currentPrompts?.array?.size
}
