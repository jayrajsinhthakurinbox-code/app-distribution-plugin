<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# App Distribution for Firebase Changelog

## [Unreleased]

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
