# Release Notes - v2.3.7

## New Features

- **Enhanced Security for Device Association:** Implemented strict device ID
  checks during account attachment. The server now verifies if the requesting
  device matches the device ID previously associated with the username.
- **Admin Override for Device ID:** Added a "Remove previous device" option in
  the Admin Interface. This allows administrators to explicitly override the
  device ID mismatch error and associate a new device with an existing username.

## UI/UX Improvements

- **Accessibility:** Added content descriptions to video player controls
  (Expand, Restart, Pause, Play) for better accessibility support.
- **String Externalization:** Extracted numerous hardcoded UI strings (Admin
  interface labels, progress counters, error messages) into `strings.xml` for
  better maintainability and localization support.
- **Progress Formatting:** Improved the formatting of prompt progress indicators
  (e.g., "1 of 10", "50%").

## Technical Changes

- **State Management Refactoring:** Separated `overviewInstructionsShown` state
  into a new `AppStatus` class, distinct from `UserSettings`, to better
  categorize application state versus user preferences.
- **Robust Parsing:** Added error handling for `PromptType` parsing to default
  to `TEXT` if an unknown type is encountered.

# Release Notes - v2.3.6

## Android Application (`2.3.6`)

**New Features & User Interface**

- **Overview Instructions:** Introduced a new global "Overview Instructions"
  feature that can appear automatically for new users.
  - Added an "Overview" button to the main Prompt Selection screen to review
    these instructions at any time.
  - Instructions can now include an interactive **Example Prompt** in addition
    to text and video.
- **Admin Controls:** Added a "Reset Overview Instructions" button in the Admin
  settings to allow clearing the "viewed" status of instructions for testing or
  re-onboarding.
- **Tablet Support:** Improved layout constraints for the Prompt Selection
  screen on tablet devices.

**Improvements & Internal Changes**

- **User Settings Storage:** Migrated user preferences (like
  `enableDismissCountdownCircle` and the new `overviewInstructionsShown`) to a
  JSON-based storage format for better extensibility.
- **Data Parsing:** Refactored `PromptsCollection`, `PromptsSection`, and
  `Prompt` parsing logic into dedicated `fromJson` factory methods.
- **Resource Management:** Updated the resource downloader to ensure assets for
  overview instructions and example prompts are properly cached.
- **Dependencies:** Added `kotlin-parcelize` plugin and updated the Android
  Gradle Plugin to `8.13.1`.

## Server (`v2.3.6`)

**New Tools**

- **Instruction Management:** Added `apply_instructions_to_prompts.py`, a
  utility script to merge instruction data (including global overview and
  section-specific instructions) into prompt JSON files.

# Release Notes - v2.3.5

## New Features

- **App Version Enforcement:** Implemented a mechanism to validate if the
  current app version is compatible with assigned prompts. The app now checks
  against server-side constraints and displays an incompatibility message if
  needed.
- **Countdown Circle Logging:** Dismissing the countdown circle now logs an
  event to the server for better tracking of user behavior.
- **Directive Management:** Added `setVersionRange` and `listUsers` commands to
  the server's `create_directive.py` tool to manage user version requirements
  and list registered users.

## Technical Changes

- **Settings Architecture:** Refactored Android `AppSettings` into
  `UserSettings` (for preferences) and `AppStatus` (for system state) to better
  organize application data.

# Release Notes - v2.3.4

## New Features

- **Countdown Dismissal:** Implemented a new feature to allow users to dismiss
  the countdown timer by tapping the circle. This feature is disabled by
  default.
- **Admin Controls:**
  - Added a hidden mechanism (tapping the "Admin Interface" header 5 times) to
    enable the countdown dismissal feature.
  - Added a "Disable Dismiss Circle" button to the Admin Interface to revert
    this setting.

## Technical Changes

- **Settings Architecture:** Introduced `AppSettings` to manage local
  app-specific settings, distinct from user prompts or global state.
- **Cleanup:** Removed unused XML layout resources (`activity_load_data.xml`).
