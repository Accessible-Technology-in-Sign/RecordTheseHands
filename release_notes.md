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

# Release Notes - v2.3.3

## Android Application

**New Features & User Interface**

- **Admin Interface Rework:** The Admin interface has been completely rebuilt
  using Jetpack Compose for a modern look and improved tablet/phone layouts. It
  is now accessible via 3 taps on the app title in the Home Screen.
  - Device ID and Username settings are now managed through dedicated input
    fields and buttons.
- **Section Instructions:** Introduced a new `InstructionsActivity` to display
  section-specific instructions, which can include both text and video.
- **Prompt Selection Screen Redesign:** The prompt selection screen has been
  redesigned with Jetpack Compose, featuring a cleaner interface and better
  handling of scrollable content for various screen sizes.
- **Tablet Layout Enhancements:** Improved adaptive layouts across the app for a
  better experience on tablet devices, including dynamic orientation settings
  and optimized component arrangements.
- **Image Prompts with Text Flow:** Integrated `TextFlow` library to
  intelligently wrap text around images in prompts, especially for smaller
  screens.

**Improvements & Internal Changes**

- **Dependency Updates:**
  - Added `androidx.compose.material:material-icons-extended:1.7.8` for
    additional UI icons.
  - Integrated `io.github.oleksandrbalan:textflow:1.2.1` for advanced text
    layout capabilities.
- **Data Manager Enhancements:**
  - `DataManager` now supports a `defaultSection` in
    `PromptsCollectionMetadata`, allowing for pre-selection of a starting
    section.
  - `attachToAccount` now provides detailed error messages and automatically
    enables tutorial mode upon successful account attachment.
  - Improved robustness of prompt reloading and resource management, including
    parallel downloading of resources for efficiency.
- **Upload Reliability:** Introduced an `INTERRUPTED` status for uploads to
  better manage and communicate temporary upload failures.
- **Codebase Refactoring:**
  - Moved `LoadDataActivity`, `HomeScreenActivity`, and `PromptSelectActivity`
    to the `home` package, reflecting their updated roles.
  - Introduced `AdminViewModel` for better separation of concerns in the Admin
    interface logic.
  - Removed outdated XML layout files.
- **Copyright Update:** Updated copyright year to 2025.

## Server

**API Changes & Improvements**

- **Prompt File Requirement for Registration:** The `/register_login` endpoint
  now supports a `must_have_prompts_file` parameter, ensuring that new users
  have an associated prompts file before their login token is set.
- **User Authentication Feedback:** The `is_authenticated_page` endpoint now
  returns the authenticated username for clearer status reporting.

**Developer Tools**

- **Login Token Output:** The `create_directive.py` script now prints the
  generated login token in a structured JSON format.

# Release Notes - v2.3.2.1

## UI/UX Improvements

- **Countdown Dismissal:** Reduced the number of taps required to dismiss the
  countdown circle from 5 to 3.

## Technical Changes

- **State Persistence:** Updated `DataManager` to ensure a default section is
  selected if one is not already set, and to guarantee that changes to the
  selected section are immediately saved to user preferences.
- **Code Refactoring:** Extracted `ServerStatus` and `ServerState` into a
  dedicated file (`ServerState.kt`) and removed unused imports in
  `HomeScreenActivity`.

# Release Notes - v2.3.2

## Android Application

**New Features & User Interface**

- **Redesigned Home Screen:** The home screen (`HomeScreenActivity`) has been
  completely refactored to use Jetpack Compose, offering a modern and more
  maintainable UI. This includes dynamic display of upload status and a more
  streamlined layout.
- **Configurable Countdown Circle:** The `CountdownCircle` now supports an
  option to hide the countdown text.

**Improvements & Internal Changes**

- **Dependency Updates:**
  - Updated `protobuf-javalite` to `4.33.0`.
  - Upgraded Compose UI version to `1.9.4`.
  - Updated WorkManager to `2.11.0`.
  - Added `androidx.lifecycle:lifecycle-viewmodel-compose` and
    `androidx.compose.runtime:runtime-livedata` for better Compose integration.
  - Updated Kotlin version to `2.2.21`.
  - Updated CameraX version to `1.5.1`.
- **Enhanced Server Communication and Error Handling:**
  - `DataManager` now checks for active network connectivity before making
    server requests.
  - Introduced a new `ServerState` class to provide more granular server
    connection status (e.g., `NO_INTERNET`, `NO_SERVER`, `SERVER_ERROR`,
    `NO_LOGIN`, `ACTIVE`).
  - Improved the `pingServer` logic to use the `/is_authenticated` endpoint for
    a more robust connection check.
  - Standardized error handling for server response codes across various
    `DataManager` network operations.
- **Button Enhancements:** The `PrimaryButton` composable now supports an
  optional `extraContent` slot, allowing for embedded UI elements like the
  countdown circle.
- **String Resource Updates:** Added new string resources for more detailed
  server status messages, upload progress, and updated existing messages in
  various languages.
- **Color Resource:** Added `very_light_gray` to colors.
- **Skip Button Text:** Changed the default text for the skip button from "Skip"
  to "Bad Prompt" in `RecordingActivity`.

## Server

**API Changes & Improvements**

- **Authentication Endpoint:** Added a new `/is_authenticated` endpoint for
  clients to check server connectivity and authentication status.
- **Standardized Unauthorized Responses:** Several API endpoints (`/prompts`,
  `/upload`, `/verify`, `/save`, `/save_state`, `/directives`,
  `/directive_completed`) now consistently return `401 Unauthorized` for invalid
  login tokens, instead of `400 Bad Request`.

**Developer Tools**

- **Password Management:** The `create_directive.py` script now includes a
  `setPassword` operation, allowing for setting a specific password or
  generating a random one for a user.
- **Random Password Generation:** `create_directive.py` now generates a random
  password for `changeUser` if not explicitly provided.
