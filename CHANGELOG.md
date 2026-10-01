<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# App Distribution for Firebase Changelog

## [Unreleased]

## [1.1.2]

### Fixed

- In the Release Signing dialog, the Save button stayed disabled after an error (such as a keystore path with a stray space), so the dialog had to be closed and reopened. Errors now clear as soon as you edit a field, and Save stays available.
- Keystore paths are cleaned up before use: surrounding spaces and quotes are removed and `~` is expanded. Paths with spaces inside them still work.

## [1.1.1]

### Fixed

- The tool window failed to open in 1.1.0 ("Nothing to show") because of how the Release / Debug choice was laid out.

## [1.1.0]

### Added

- Choose between a Release and a Debug build. Debug builds are signed with the debug key, so no keystore is needed, and they're marked as debug builds in the tool window and in Slack.
- "Choose an APK file…" also finds debug APKs, and the build type is read from the APK itself.

## [1.0.1]

### Fixed

- Compatibility with Android Studio / IntelliJ IDEA 2024.3: resolving the project's Gradle JDK no longer relies on an API that only exists in 2025.1+.

## [1.0.0]

### Added

- Build a signed release APK of the app module from a tool window, using the project's Gradle JDK.
- Distribute an APK to testers with Firebase App Distribution, with release notes.
- Remember testers per project, with one-click recent testers.
- Pick one APK when product flavors produce several; distribute the last build or any APK file.
- Release signing for projects that sign through *Generate Signed App Bundle / APK*, stored in the IDE password store.
- Firebase CLI detection, standalone download (no Node.js) and in-IDE sign-in.
- Live progress with cancel, and a link to the release in the Firebase console.
- Optional Slack announcements through an Incoming Webhook.
