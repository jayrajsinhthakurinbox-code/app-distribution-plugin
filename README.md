# App Distribution for Firebase

Android Studio plugin that builds a signed release APK and sends it to your
testers with [Firebase App Distribution](https://firebase.google.com/docs/app-distribution),
optionally announcing it in Slack.

*Not affiliated with or endorsed by Google. Firebase is a trademark of Google LLC.*

## Features

- **One-click release build** of your app module, using the same Gradle JDK
  as the IDE. Works with product flavors, custom APK names and projects that
  sign through *Build › Generate Signed App Bundle / APK* (the keystore is
  remembered in the IDE password store).
- **Distribute to testers** with release notes; recent testers are remembered
  per project.
- **Live progress with cancel**, and a link to the release in the Firebase
  console.
- **Firebase CLI handled for you**: uses an installed `firebase`, or downloads
  the standalone CLI into `~/.app-distribution/bin` (no Node.js), with sign-in
  from the tool window.
- **Optional Slack announcements** via an Incoming Webhook
  (*Settings › Tools › App Distribution for Firebase*).

## Requirements

- Android Studio Meerkat (2024.3) or newer (or IntelliJ IDEA 2024.3+ with Android support)
- A Firebase project with App Distribution, and its `google-services.json` in
  your app module (or `src/<variant>/`)
- Release signing: in Gradle, or entered once in the plugin

## Development

The plugin bundles the [`appdist` CLI](https://github.com/jayrajsinhthakurinbox-code/app-distribution-cli),
which does the building and uploading. Clone both repositories side by side:

```bash
git clone https://github.com/jayrajsinhthakurinbox-code/app-distribution-cli.git
git clone https://github.com/jayrajsinhthakurinbox-code/app-distribution-plugin.git
cd app-distribution-plugin
```

```bash
./gradlew runIde        # sandbox Android Studio with the plugin
./gradlew buildPlugin   # build/distributions/app-distribution-plugin-<version>.zip
```

The build compiles against your local Android Studio
(`/Applications/Android Studio.app`; override with
`-PandroidStudioPath=…/Contents`) and the CLI from `../app-distribution-cli`
(override with `-PappDistributionCliDir=…`).

To iterate on the CLI without rebuilding the plugin, set `APPDIST_CLI` to
`../app-distribution-cli/build/install/appdist/bin/appdist` in the run
configuration's environment.

## Publishing to the JetBrains Marketplace

1. **First release (manual):** create a vendor profile at
   <https://plugins.jetbrains.com>, then *Upload plugin* with the zip from
   `./gradlew buildPlugin`. JetBrains reviews new plugins before they go
   live (usually 1–2 business days).
2. **Signing** (required for updates):
   generate a key and certificate as described in
   [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html),
   then export them as `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and
   `PRIVATE_KEY_PASSWORD`.
3. **Updates:** create a token at <https://plugins.jetbrains.com/author/me/tokens>,
   export it as `PUBLISH_TOKEN`, bump `version` in `gradle.properties`, add a
   section to `CHANGELOG.md`, and run:
   ```bash
   ./gradlew publishPlugin
   ```

Before publishing, `./gradlew verifyPlugin` checks compatibility against the
recommended IDE versions (downloads them the first time).

## License

[MIT](LICENSE)
