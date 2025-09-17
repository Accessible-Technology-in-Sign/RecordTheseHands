package edu.gatech.ccg.recordthesehands.upload

data class PromptState(
  // Core State
  val tutorialMode: Boolean,
  val promptsCollection: PromptsCollection?,
  val promptProgress: Map<String, Map<String, Int>>,
  val currentSectionName: String?,
  val username: String?,
  val deviceId: String?,

  // Derived State (for UI convenience)
  val currentPrompts: Prompts?,
  val currentPromptIndex: Int?,
  val totalPromptsInCurrentSection: Int?
)
