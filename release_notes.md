# Release Notes - v2.3.10

## Server

**New Features & Tools**

- **Statistics Generation:** Added a new `stats` command to
  `create_directive.py` that generates comprehensive user statistics from data
  dumps.
- **Tutorial Prompt Merging:** Updated `apply_instructions_to_prompts.py` to
  support merging tutorial prompts from a separate JSON file.

**Bug Fixes**

- **Login:** Fixed a `flask-login` issue in `home_page` that would make users
  unable to log in if they had an invalid `remember_token`.
- **Version Check:** Fixed a bug so that the server side version check now
  correctly thinks v2.3.9 < v2.3.10.

**Refactoring & Cleanup**

- **Code Cleanup:** Removed several deprecated scripts (`dump_clips.py`,
  `phrases_to_prompts.py`, `upload_file.py`, `upload_mixed_prompts.py`).
- **Lint:** Addressed all lint warnings in python scripts.

# Release Notes - v2.3.9

## Server

**Bug Fixes**

- **Save Endpoint Fix:** Corrected a variable reference error in the `/save`
  endpoint that prevented prompt progress from being correctly updated.
- **Updated App version:** The Android versionCode and versionName for the app
  hadn't been updated since v1.3.1 .

# Release Notes - v2.3.8

## Android Application

**Technical Changes**

- **Enhanced Data Persistence:** Improved `DataManager` with thread-safe
  singleton initialization and locking for user settings and application status
  updates.
- **State Management:** Refactored `AppStatus` and `UserSettings` retrieval to
  ensure reliable JSON parsing and consistent state synchronization with the
  persistent store.

## Server

**New Features & User Interface**

- **Video Review Enhancements:** The `/video` page now includes a "Show Clips"
  toggle, allowing administrators to preview individual clips directly in the
  browser with "Go to start/end" controls, or generate `ffplay` commands for
  local playback.
- **User Management Dashboard:** Improved the `/users` page with color-coded
  heartbeat indicators (green for recent, blue for hours, purple for days, red
  for longer) to quickly assess user activity.
- **Registration Access:** Added a direct "Register" link to the login page for
  easier account creation.
- **Progress Tracking:** Bug fix: The `/save` endpoint now tracks the maximum
  prompt index reached in each section, storing it in `heartbeat/max_prompt` for
  better progress monitoring.

**Developer Tools**

- **Database Management:** Added new commands to `create_directive.py`
  (`userData`, `saveDatabase`, `restoreUser`, `deleteUser`) for backing up,
  restoring, and deleting user data, utilizing concurrent execution for
  performance.
- **Cleanup:** Removed the deprecated `upload_apk.py` script.
- **Documentation:** Added technical summaries for both the Android app and
  Python server to `README.md`.

**Refactoring**

- **Code Quality:** Applied linting and improved docstrings across `main.py` and
  `create_directive.py`.

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

# Release Notes - v2.3.1

## Android Application

**New Features & User Interface**

- **Skip & Explain Workflow:** Added the ability for users to skip a prompt
  during recording.
  - Clicking "Skip" triggers an "Explain" mode with a 15-second countdown.
  - Users can record an explanation for why they are skipping the prompt.
  - Added visual cues ("Explain" text overlay, countdown circle) during this
    phase.
- **APK Download (Admin):** Added a "Download APK" button to the "Load Data"
  (Admin) activity for easier app distribution.

**Technical Changes**

- **Metadata Tracking:** Updated `ClipDetails` to include an `isSkipExplanation`
  flag, allowing the server to distinguish between normal recordings and skip
  explanations.

## Server

**New Features**

- **APK Download (Web):** Added a direct "Download APK" link to the server's
  login and home pages.
- **Dynamic APK Serving:** The server now scans the storage bucket for APK files
  matching the version pattern and automatically serves the latest version,
  eliminating the need for hardcoded filenames.

# Release Notes - v2.3.0

## Android Application

**New Features & User Interface**

- **Major UI Refactor to Jetpack Compose:** The `RecordingActivity` and
  `HomeScreenActivity` have been completely rewritten using Jetpack Compose.
- **Structured Prompt Management:** Introduced `PromptsCollection` and related
  classes to better organize main and tutorial prompts.
- **ViewModel Integration:** Implemented `RecordingViewModel` to manage UI state
  and logic for `RecordingActivity`.
- **Composable UI Elements:** Created reusable UI components for buttons,
  indicators, and timers to ensure consistency.
- **Configurable Countdown Circle:** Updated the `CountdownCircle` component to
  support hiding the countdown text.

**Improvements & Internal Changes**

- **Dependency Updates:** Updated Kotlin to `2.2.20`, Android Gradle Plugin to
  `8.13.0`, and various libraries to their latest stable versions.
- **Robust Background Uploads:** Replaced `UploadService` with a
  WorkManager-based solution for more reliable background data uploads.
- **Enhanced Data Management (`DataManager`):** Refactored `DataManager` with
  `Mutex` for thread safety, improved persistence handling, and atomic saving
  operations.
- **Camera2 API Migration:** Migrated `RecordingActivity` from CameraX to the
  Camera2 API for finer control over video recording parameters.
- **Comprehensive String Resources:** Added extensive string resources for
  better localization and UI clarity.
- **Extended Color Palette:** Added new color definitions for a richer UI
  design.
- **Layout Cleanup:** Removed manual constraint adjustments, leveraging Jetpack
  Compose for dynamic resizing.
- **Copyright Update:** Updated copyright notice to 2025.

## Server

**API Changes & Improvements**

- **Dynamic APK Serving:** The `/apk` endpoint now dynamically serves the latest
  APK version found in the GCS bucket.
- **Simplified Prompt Directives:** Consolidated prompt downloading into a
  single `setPrompts` directive and added `reloadPrompts` to refresh client
  data.
- **App Engine Scaling:** Optimized `app.yaml` to use `F1` instances with
  automatic scaling.

**Developer Tools**

- **Video Post-processing Pipeline:** Added `local_video_pipeline.py` to
  automate metadata dumping, downloading, and clip generation.
- **Enhanced Video Clipping:** Updated `clip_video.py` to use `ffprobe` for
  accurate keyframe identification and parallel processing.
- **Improved Video Downloading:** Updated `download_videos.py` to use concurrent
  downloads and MD5 validation.
- **Metadata Dumping:** Refined `dump_clips.py` logic for extracting video
  metadata into CSV and JSON formats.
- **Environment Setup:** Updated `setup_local_env.sh` to include
  `google-cloud-cli` and `pyink` installation.
