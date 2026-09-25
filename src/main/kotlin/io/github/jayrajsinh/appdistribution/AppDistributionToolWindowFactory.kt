package io.github.jayrajsinh.appdistribution

import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Rectangle
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private const val TITLE = "App Distribution for Firebase"

class AppDistributionToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow
    ) {
        val panel = ReleasePanel(project)

        val content = ContentFactory
            .getInstance()
            .createContent(panel, null, false)

        toolWindow.contentManager.addContent(content)
    }

    /**
     * Three steps: Build → Distribute → Done.
     */
    private class ReleasePanel(
        private val project: Project
    ) : JBPanel<ReleasePanel>() {

        // Build step
        private var buildButton: JButton? = null
        private var buildCancelButton: JButton? = null
        private var buildTask: DistributionCli.Task? = null
        private var buildProgress: JProgressBar? = null
        private var buildStatus: JBLabel? = null
        private var buildAnimator: ProgressAnimator? = null

        // Distribute step
        private val testers = linkedSetOf<String>()
        private var releaseNotesDraft = ""

        // Firebase account row; declared before init, which builds the UI
        private val firebaseStatus = JBLabel()

        private var firebaseActionHandler: () -> Unit = {}

        private val firebaseAction = ActionLink("") {
            firebaseActionHandler()
        }

        private val properties = PropertiesComponent.getInstance(project)

        // "Signing: …" row in the Build step
        private val signingRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
        }

        init {
            layout = BorderLayout()

            showBuildStep()
        }

        // =========================================================
        // STEP 1: BUILD
        // =========================================================

        private fun showBuildStep() {
            removeAll()

            buildAnimator?.stop()

            val progress = JProgressBar(0, ProgressAnimator.RESOLUTION).apply {
                isVisible = false
            }

            val status = JBLabel(
                "Ready when you are"
            ).apply {
                foreground = UIUtil.getContextHelpForeground()
            }

            val button = primaryButton(
                "Build Release APK"
            ) {
                buildRelease()
            }

            val cancelButton = JButton("Cancel").apply {
                isVisible = false
                addActionListener {
                    isEnabled = false
                    text = "Cancelling…"
                    buildTask?.cancel()
                }
            }

            buildButton = button
            buildCancelButton = cancelButton
            buildProgress = progress
            buildStatus = status
            buildAnimator = ProgressAnimator(progress, status)

            val lastBuild = loadReleaseManifest()
                ?.takeIf { File(it.apkPath).isFile }

            val projectName = project.name

            val content = panel {

                header(
                    "Send a test build to your QA and beta testers " +
                        "with Firebase App Distribution."
                )

                row {
                    cell(stepper(current = 1))
                }.bottomGap(BottomGap.SMALL)

                group("Firebase Account") {
                    row {
                        cell(firebaseStatus)
                        cell(firebaseAction)
                    }
                }

                group("Build") {
                    row {
                        text(
                            "Builds a signed release APK of " +
                                "<b>${StringUtil.escapeXmlEntities(projectName)}</b>."
                        )
                    }
                    row {
                        cell(signingRow)
                    }
                    row {
                        cell(progress).align(AlignX.FILL)
                    }.topGap(TopGap.SMALL)
                    row {
                        cell(status)
                    }
                    row {
                        cell(button).align(AlignX.FILL).resizableColumn()
                        cell(cancelButton)
                    }.topGap(TopGap.SMALL)
                }

                group("Already Have a Build?") {
                    if (lastBuild != null) {
                        row {
                            icon(AllIcons.Actions.Redo)
                            link(
                                "Distribute last build: " +
                                    "${lastBuild.versionName} (${lastBuild.versionCode})"
                            ) {
                                showDistributeStep(lastBuild)
                            }
                            comment(builtAgo(File(lastBuild.apkPath)))
                        }
                    }
                    row {
                        icon(AllIcons.Actions.MenuOpen)
                        link("Choose an APK file…") {
                            chooseApkFile()
                        }
                    }
                }
            }

            content.border = JBUI.Borders.empty(12, 16)

            add(
                scrollable(content),
                BorderLayout.CENTER
            )

            renderSigningRow()

            revalidate()
            repaint()

            refreshFirebaseStatus()
        }

        /** Shows where release signing comes from, with change / remove. */
        private fun renderSigningRow() {
            signingRow.removeAll()

            val saved = ReleaseSigning.savedSummary(project)

            signingRow.add(JBLabel(AllIcons.Nodes.SecurityRole))

            if (saved == null) {
                signingRow.add(JBLabel("Signing: from the project's Gradle settings").apply {
                    foreground = UIUtil.getContextHelpForeground()
                })
                signingRow.add(ActionLink("Use a keystore…") {
                    askForSigning(thenBuild = false)
                })
            } else {
                val (storeFile, alias) = saved

                signingRow.add(JBLabel("Signing: ${File(storeFile).name} · $alias").apply {
                    toolTipText = storeFile
                })
                signingRow.add(ActionLink("Change") {
                    askForSigning(thenBuild = false)
                })
                signingRow.add(ActionLink("Remove") {
                    ReleaseSigning.clear(project)
                    renderSigningRow()
                })
            }

            signingRow.revalidate()
            signingRow.repaint()
        }

        /**
         * Asks for the release keystore (pre-filled from Android Studio's
         * signing wizard) and saves it for this project.
         */
        private fun askForSigning(
            thenBuild: Boolean
        ) {
            val dialog = SigningDialog(
                project,
                ReleaseSigning.savedSummary(project)
                    ?: ReleaseSigning.wizardDefaults(project)
            )

            val signing = if (dialog.showAndGet()) dialog.result else null

            if (signing == null) {
                return
            }

            // Password Safe can be slow (keychain), so save off the EDT
            val saved = ProgressManager.getInstance()
                .runProcessWithProgressSynchronously<Boolean, Exception>(
                    { ReleaseSigning.save(project, signing) },
                    "Saving Keystore",
                    false,
                    project
                )

            renderSigningRow()

            if (!saved) {
                Messages.showWarningDialog(
                    project,
                    "The keystore passwords couldn't be saved, so you'll be asked " +
                        "for them again next time.\n\nCheck Settings › Appearance & " +
                        "Behavior › System Settings › Passwords and choose " +
                        "\"In native Keychain\".",
                    "Release Signing"
                )
            }

            if (thenBuild) {
                buildRelease(signingOverride = signing)
            }
        }

        private fun chooseApkFile() {
            val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
                .withExtensionFilter("apk")
                .withTitle("Choose APK to Distribute")

            FileChooser.chooseFile(descriptor, project, newestReleaseApk()) { file ->
                buildRelease(apk = file.path)
            }
        }

        /**
         * Newest release APK in the usual places (pre-selected in the file
         * chooser), or the folder it would be in.
         */
        private fun newestReleaseApk(): VirtualFile? {
            val projectPath = project.basePath ?: return null
            val app = File(projectPath, "app")

            val candidates = listOf(
                // Build › Generate Signed App Bundle / APK
                File(app, "release"),
                // Gradle output, including flavors (apk/<flavor>/release)
                File(app, "build/outputs/apk")
            )

            val newest = candidates
                .filter { it.isDirectory }
                .flatMap { dir ->
                    dir.walkTopDown()
                        .filter { it.isFile && it.extension == "apk" }
                        .filter { it.parentFile.name.endsWith("release", ignoreCase = true) }
                        .toList()
                }
                .maxByOrNull { it.lastModified() }

            val target = newest
                ?: candidates.firstOrNull { it.isDirectory }
                ?: app.takeIf { it.isDirectory }
                ?: File(projectPath)

            return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)
        }

        /**
         * Builds the release APK. With [apk], skips the build and prepares
         * that APK instead (a chosen file, or one of several flavor APKs).
         */
        private fun buildRelease(
            apk: String? = null,
            signingOverride: ReleaseSigning? = null
        ) {
            val button = buildButton ?: return
            val projectPath = project.basePath ?: run {
                showBuildError("Android project path could not be determined.")
                return
            }

            // A keystore is configured but its passwords can't be read:
            // ask now rather than building an unsigned APK
            val signing = signingOverride ?: if (apk == null && ReleaseSigning.savedSummary(project) != null) {
                ProgressManager.getInstance()
                    .runProcessWithProgressSynchronously<ReleaseSigning?, Exception>(
                        { ReleaseSigning.load(project) },
                        "Reading Keystore",
                        false,
                        project
                    )
                    ?: run {
                        askForSigning(thenBuild = true)
                        return
                    }
            } else {
                null
            }

            button.isEnabled = false
            button.text = if (apk == null) "Building…" else "Preparing…"

            val task = DistributionCli.Task()
            buildTask = task

            buildCancelButton?.apply {
                isVisible = true
                isEnabled = true
                text = "Cancel"
            }

            buildStatus?.icon = null
            buildProgress?.isVisible = true

            buildAnimator?.start(
                if (apk == null) "Starting build" else "Reading APK"
            )

            val args = listOf("release") +
                (apk?.let { listOf("--apk", it) } ?: emptyList())

            val worker = object : SwingWorker<DistributionCli.Result, String>() {

                override fun doInBackground(): DistributionCli.Result =
                    DistributionCli.run(
                        args = args,
                        directory = File(projectPath),
                        environment =
                            // Build with the project's Gradle JDK, like the IDE
                            GradleJdk.environment(project) +
                                // Keystore for projects signed via the IDE wizard
                                (signing?.gradleEnvironment() ?: emptyMap()),
                        task = task,
                        onLine = { publish(it) }
                    )

                override fun process(chunks: MutableList<String>) {
                    chunks.forEach { line ->
                        parseProgress(line)?.let { (percent, message) ->
                            buildAnimator?.update(percent, message)
                        }
                    }
                }

                override fun done() {
                    val result = try {
                        get()
                    } catch (e: Exception) {
                        showBuildError(e.cause?.message ?: e.message ?: "Unknown error")
                        return
                    }

                    val output = result.output

                    buildCancelButton?.isVisible = false

                    when {
                        result.cancelled -> {
                            resetBuildButton()
                            buildAnimator?.stop()
                            buildProgress?.isVisible = false
                            buildStatus?.icon = null
                            buildStatus?.text = "Build cancelled"
                        }

                        result.exitCode == 0 ->
                            // Let the bar finish filling before moving on
                            buildAnimator?.update(100, "Build ready") {
                                markerValue(output, "APPDIST_MANIFEST")?.let {
                                    properties.setValue(LAST_MANIFEST_KEY, it)
                                }

                                val manifest = loadReleaseManifest()

                                if (manifest != null) {
                                    showDistributeStep(manifest)
                                } else {
                                    showBuildError(
                                        "The build finished but its details were not found."
                                    )
                                }
                            }

                        hasMarker(output, "APPDIST_MULTIPLE_APKS") ->
                            chooseFlavorApk(
                                markerValue(output, "APPDIST_MULTIPLE_APKS")
                                    .orEmpty()
                                    .split("|")
                                    .filter { it.isNotBlank() },
                                projectPath
                            )

                        hasMarker(output, "APPDIST_UNSIGNED") -> {
                            // Project signs via the IDE wizard: ask for the
                            // keystore once, then build again
                            resetBuildButton()
                            buildAnimator?.stop()
                            buildProgress?.isVisible = false
                            buildStatus?.icon = AllIcons.General.Warning
                            buildStatus?.text = "Release signing needed"

                            askForSigning(thenBuild = true)
                        }

                        else ->
                            showBuildError(failureReason(output))
                    }
                }
            }

            worker.execute()
        }

        /** Several release APKs (product flavors): let the user pick one. */
        private fun chooseFlavorApk(
            apks: List<String>,
            projectPath: String
        ) {
            if (apks.isEmpty()) {
                showBuildError("Several release APKs were built but none could be listed.")
                return
            }

            buildAnimator?.stop()
            buildStatus?.text = "Several flavors were built. Choose one."

            val labels = apks.associateBy {
                it.removePrefix(projectPath).removePrefix(File.separator)
            }

            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(labels.keys.toList())
                .setTitle("Which APK Should Testers Get?")
                .setItemChosenCallback { label ->
                    buildRelease(apk = labels.getValue(label))
                }
                .addListener(object : JBPopupListener {
                    override fun onClosed(event: LightweightWindowEvent) {
                        if (!event.isOk) {
                            resetBuildButton()
                            buildProgress?.isVisible = false
                            buildStatus?.text = "Ready when you are"
                        }
                    }
                })
                .createPopup()
                .showInCenterOf(this)
        }

        private fun showBuildError(
            message: String
        ) {
            resetBuildButton()

            buildAnimator?.stop()
            buildProgress?.isVisible = false

            buildStatus?.text = "Build failed"
            buildStatus?.icon = AllIcons.General.Error

            Messages.showErrorDialog(
                this,
                message,
                "Build Failed"
            )
        }

        private fun resetBuildButton() {
            buildButton?.isEnabled = true
            buildButton?.text = "Build Release APK"
            buildCancelButton?.isVisible = false
        }

        // =========================================================
        // FIREBASE ACCOUNT
        // =========================================================

        /** Checks Firebase CLI + sign-in up front, so nobody finds out at upload. */
        private fun refreshFirebaseStatus() {
            setFirebaseStatus(
                AnimatedIcon.Default.INSTANCE,
                "Checking Firebase…"
            )

            runCli(
                listOf("auth", "status")
            ) { exitCode, output ->

                when {
                    exitCode == 0 -> setFirebaseStatus(
                        AllIcons.General.InspectionsOK,
                        markerValue(output, "APPDIST_ACCOUNT") ?: "Signed in"
                    )

                    hasMarker(output, "APPDIST_FIREBASE_MISSING") -> setFirebaseStatus(
                        AllIcons.General.Warning,
                        "Firebase CLI is not installed",
                        "Install"
                    ) {
                        installFirebaseCli { refreshFirebaseStatus() }
                    }

                    exitCode == 3 -> setFirebaseStatus(
                        AllIcons.General.Warning,
                        "Not signed in",
                        "Sign in"
                    ) {
                        signInToFirebase { refreshFirebaseStatus() }
                    }

                    else -> setFirebaseStatus(
                        AllIcons.General.Error,
                        "Couldn't check Firebase",
                        "Retry"
                    ) {
                        refreshFirebaseStatus()
                    }
                }
            }
        }

        private fun setFirebaseStatus(
            icon: Icon,
            text: String,
            actionText: String? = null,
            action: () -> Unit = {}
        ) {
            firebaseStatus.icon = icon
            firebaseStatus.text = text

            firebaseAction.text = actionText.orEmpty()
            firebaseAction.isVisible = actionText != null
            firebaseActionHandler = action
        }

        /**
         * Firebase sign-in without a terminal: open the Firebase login page,
         * then ask for the authorization code it shows.
         */
        private fun signInToFirebase(
            onResult: (Boolean) -> Unit
        ) {

            runCli(
                listOf("auth", "start")
            ) { exitCode, output ->

                val url = markerValue(output, "APPDIST_LOGIN_URL")
                val session = markerValue(output, "APPDIST_LOGIN_SESSION")

                if (exitCode != 0 || url == null) {
                    Messages.showErrorDialog(
                        project,
                        "Could not start Firebase sign-in.\n\n" +
                            failureReason(output),
                        "Sign in to Firebase"
                    )
                    onResult(false)
                    return@runCli
                }

                BrowserUtil.browse(url)

                val code = Messages.showInputDialog(
                    project,
                    "Firebase sign-in has opened in your browser.\n" +
                        "Sign in with your Google account, then paste the " +
                        "authorization code here." +
                        (session?.let { "\n\nSession ID: $it" } ?: ""),
                    "Sign in to Firebase",
                    AllIcons.General.User
                )?.trim()

                if (code.isNullOrEmpty()) {
                    onResult(false)
                    return@runCli
                }

                runCli(
                    listOf("auth", "complete", code)
                ) { completeExitCode, completeOutput ->

                    if (completeExitCode == 0) {
                        onResult(true)
                    } else {
                        Messages.showErrorDialog(
                            project,
                            "Firebase sign-in failed. Check the code and try again.\n\n" +
                                failureReason(completeOutput),
                            "Sign in to Firebase"
                        )
                        onResult(false)
                    }
                }
            }
        }

        private fun installFirebaseCli(
            onResult: (Boolean) -> Unit
        ) {

            val answer = Messages.showYesNoDialog(
                project,
                "Firebase CLI is needed to upload builds.\n\n" +
                    "Download it now? It's a one-time download (~270 MB) " +
                    "into ~/.app-distribution/bin and needs no Node.js or admin rights.",
                "Install Firebase CLI",
                "Install",
                "Cancel",
                Messages.getQuestionIcon()
            )

            if (answer != Messages.YES) {
                onResult(false)
                return
            }

            setFirebaseStatus(
                AnimatedIcon.Default.INSTANCE,
                "Installing Firebase CLI (~270 MB)…"
            )

            runCli(
                listOf("firebase", "--yes")
            ) { exitCode, output ->

                if (exitCode == 0) {
                    onResult(true)
                } else {
                    Messages.showErrorDialog(
                        project,
                        "Firebase CLI installation failed.\n\n" +
                            failureReason(output),
                        "Install Firebase CLI"
                    )
                    onResult(false)
                }
            }
        }

        // =========================================================
        // STEP 2: DISTRIBUTE
        // =========================================================

        private fun showDistributeStep(
            manifest: ReleaseManifest
        ) {
            removeAll()

            buildAnimator?.stop()

            // Start from the testers used last time for this project
            if (testers.isEmpty()) {
                testers.addAll(lastTesters())
            }

            val notes = JBTextArea(releaseNotesDraft).apply {
                lineWrap = true
                wrapStyleWord = true
                emptyText.text = "What changed, and what should testers check?"
                border = JBUI.Borders.empty(6)
            }

            val notesCounter = JBLabel().apply {
                font = JBUI.Fonts.smallFont()
                horizontalAlignment = SwingConstants.RIGHT
            }

            val distributeButton = primaryButton("Distribute")

            val footerHint = JBLabel().apply {
                foreground = UIUtil.getContextHelpForeground()
                font = JBUI.Fonts.smallFont()
                horizontalAlignment = SwingConstants.CENTER
            }

            val uploadProgress = JProgressBar(0, ProgressAnimator.RESOLUTION).apply {
                isVisible = false
            }

            val uploadStatus = JBLabel().apply {
                foreground = UIUtil.getContextHelpForeground()
                isVisible = false
            }

            val uploadAnimator = ProgressAnimator(uploadProgress, uploadStatus)

            var uploading = false
            var uploadTask: DistributionCli.Task? = null

            val cancelUploadButton = JButton("Cancel").apply {
                isVisible = false
                addActionListener {
                    isEnabled = false
                    text = "Cancelling…"
                    uploadTask?.cancel()
                }
            }

            val updateState = {
                val notesLength = notes.text.length
                val notesTooLong = notesLength > MAX_RELEASE_NOTES

                notesCounter.text = "%,d / %,d".format(notesLength, MAX_RELEASE_NOTES)
                notesCounter.foreground =
                    if (notesTooLong) JBColor.RED else UIUtil.getContextHelpForeground()

                distributeButton.isEnabled =
                    !uploading && testers.isNotEmpty() && !notesTooLong

                if (!uploading) {
                    distributeButton.text = when (testers.size) {
                        0 -> "Distribute"
                        1 -> "Distribute to 1 Tester"
                        else -> "Distribute to ${testers.size} Testers"
                    }
                }

                footerHint.text = when {
                    uploading -> "You can keep working while it uploads"
                    testers.isEmpty() -> "Add at least one tester"
                    notesTooLong -> "Release notes are too long"
                    else -> "Testers get an email with a download link"
                }
            }

            val testersSection = TestersSection(onChange = updateState)

            notes.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = changed()
                override fun removeUpdate(e: DocumentEvent) = changed()
                override fun changedUpdate(e: DocumentEvent) = changed()

                fun changed() {
                    releaseNotesDraft = notes.text
                    updateState()
                }
            })

            updateState()

            distributeButton.addActionListener {
                uploading = true
                distributeButton.isEnabled = false
                distributeButton.text = "Distributing…"

                val task = DistributionCli.Task()
                uploadTask = task

                cancelUploadButton.isVisible = true
                cancelUploadButton.isEnabled = true
                cancelUploadButton.text = "Cancel"

                uploadProgress.isVisible = true
                uploadStatus.isVisible = true
                uploadAnimator.start("Starting upload")

                updateState()

                distribute(
                    manifest = manifest,
                    testers = testers.toList(),
                    releaseNotes = notes.text.trim(),
                    task = task,
                    onProgress = { percent, message ->
                        uploadAnimator.update(percent, message)
                    },
                    onStatus = { uploadStatus.text = it },
                    onFinished = {
                        uploading = false
                        cancelUploadButton.isVisible = false
                        uploadAnimator.stop()
                        uploadProgress.isVisible = false
                        uploadStatus.isVisible = false
                        updateState()
                    }
                )
            }

            val content = panel {

                header("Choose who gets this build and tell them what to test.")

                row {
                    cell(stepper(current = 2))
                }.bottomGap(BottomGap.SMALL)

                row {
                    cell(buildCard(manifest)).align(AlignX.FILL)
                }

                group("Testers") {
                    row {
                        cell(testersSection.input)
                            .align(AlignX.FILL)
                            .resizableColumn()

                        button("Add") {
                            testersSection.submitInput()
                        }
                    }
                    row {
                        cell(testersSection.error)
                    }
                    row {
                        cell(testersSection.list).align(AlignX.FILL)
                    }
                    row {
                        cell(testersSection.suggestions).align(AlignX.FILL)
                    }
                }

                // Takes the remaining vertical space
                group("Release Notes") {
                    row {
                        cell(
                            JBScrollPane(notes).apply {
                                minimumSize = JBUI.size(0, 110)
                                preferredSize = JBUI.size(0, 150)
                            }
                        ).align(Align.FILL)
                    }.resizableRow()
                    row {
                        cell(notesCounter).align(AlignX.RIGHT)
                    }
                }.resizableRow()
            }

            content.border = JBUI.Borders.empty(12, 16, 4, 16)

            // Sticky footer with the primary action
            val footer = panel {
                row {
                    cell(uploadProgress).align(AlignX.FILL)
                }
                row {
                    cell(uploadStatus)
                }
                row {
                    cell(distributeButton).align(AlignX.FILL).resizableColumn()
                    cell(cancelUploadButton)
                }
                row {
                    cell(footerHint).align(AlignX.FILL)
                }
            }.apply {
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLineTop(JBColor.border()),
                    JBUI.Borders.empty(10, 16)
                )
            }

            add(scrollable(content), BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)

            revalidate()
            repaint()

            testersSection.input.requestFocusInWindow()
        }

        /** Summary of the build being distributed. */
        private fun buildCard(
            manifest: ReleaseManifest
        ): JComponent {

            val apk = File(manifest.apkPath)

            val details = buildList {
                add(apk.name)
                if (apk.exists()) {
                    add(StringUtil.formatFileSize(apk.length()))
                    add(builtAgo(apk))
                }
            }.joinToString("  ·  ")

            val text = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false

                add(JBLabel("${manifest.displayName}  ${manifest.versionName} (${manifest.versionCode})").apply {
                    font = JBUI.Fonts.label().asBold()
                })
                add(copyableValue(manifest.applicationId).apply {
                    alignmentX = LEFT_ALIGNMENT
                })
                add(JBLabel(details).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    font = JBUI.Fonts.smallFont()
                    toolTipText = manifest.apkPath
                })
            }

            return JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
                background = JBUI.CurrentTheme.CustomFrameDecorations.paneBackground()
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(JBColor.border()),
                    JBUI.Borders.empty(10, 12)
                )

                add(JBLabel(AllIcons.FileTypes.Archive).apply {
                    verticalAlignment = SwingConstants.TOP
                }, BorderLayout.WEST)

                add(text, BorderLayout.CENTER)

                add(ActionLink("Change") { showBuildStep() }.apply {
                    verticalAlignment = SwingConstants.TOP
                }, BorderLayout.EAST)
            }
        }

        /**
         * Runs `appdist distribute`. If Firebase CLI is missing or the user
         * isn't signed in, resolves that and retries. [onFinished] is called
         * exactly once, when the flow ends.
         */
        private fun distribute(
            manifest: ReleaseManifest,
            testers: List<String>,
            releaseNotes: String,
            task: DistributionCli.Task,
            onProgress: (Int, String) -> Unit,
            onStatus: (String) -> Unit,
            onFinished: () -> Unit
        ) {

            val retry = {
                distribute(manifest, testers, releaseNotes, task, onProgress, onStatus, onFinished)
            }

            // Temp files handed to the CLI; removed once it exits
            val testersFile: File
            val releaseNotesFile: File

            try {
                testersFile = FileUtil.createTempFile("appdist-testers", ".txt", true)
                    .apply { writeText(testers.joinToString("\n")) }

                releaseNotesFile = FileUtil.createTempFile("appdist-release-notes", ".txt", true)
                    .apply { writeText(releaseNotes) }
            } catch (e: Exception) {
                onFinished()
                Messages.showErrorDialog(
                    project,
                    "Could not prepare the upload: ${e.message}",
                    "Distribution Failed"
                )
                return
            }

            runCli(
                args = listOf(
                    "distribute",
                    "--testers-file", testersFile.absolutePath,
                    "--release-notes-file", releaseNotesFile.absolutePath
                ),
                task = task,
                // Optional Slack webhook from Settings
                environment = { AppDistributionSettings.getInstance().distributeEnvironment() },
                onLine = { line ->
                    parseProgress(line)?.let { (percent, message) ->
                        onProgress(percent, message)
                    }
                }
            ) { exitCode, output ->

                testersFile.delete()
                releaseNotesFile.delete()

                when {
                    task.isCancelled -> {
                        onFinished()
                        Messages.showInfoMessage(
                            project,
                            "The upload was cancelled. Testers were not notified.",
                            "Distribution Cancelled"
                        )
                    }

                    exitCode == 0 -> {
                        onFinished()
                        rememberTesters(testers)
                        releaseNotesDraft = ""
                        showDoneStep(manifest, testers.size, output)
                    }

                    hasMarker(output, "APPDIST_AUTH_REQUIRED") -> {
                        onStatus("Waiting for Firebase sign-in…")

                        signInToFirebase { signedIn ->
                            if (signedIn) retry() else onFinished()
                        }
                    }

                    hasMarker(output, "APPDIST_FIREBASE_MISSING") -> {
                        onStatus("Installing Firebase CLI…")

                        installFirebaseCli { installed ->
                            if (installed) retry() else onFinished()
                        }
                    }

                    else -> {
                        onFinished()
                        Messages.showErrorDialog(
                            project,
                            "The build could not be sent to testers.\n\n" +
                                failureReason(output),
                            "Distribution Failed"
                        )
                    }
                }
            }
        }

        // =========================================================
        // STEP 3: DONE
        // =========================================================

        private fun showDoneStep(
            manifest: ReleaseManifest,
            testerCount: Int,
            output: List<String>
        ) {
            removeAll()

            val consoleUrl = markerValue(output, "APPDIST_CONSOLE_URL")

            val slack = markerValue(output, "APPDIST_SLACK")
            val slackStatus = slack?.substringBefore("|")
            val slackDetail = slack?.substringAfter("|", "").orEmpty()

            val who = if (testerCount == 1) "1 tester" else "$testerCount testers"

            val content = panel {

                header("Your build is on its way.")

                row {
                    cell(stepper(current = 3))
                }.bottomGap(BottomGap.MEDIUM)

                row {
                    icon(AllIcons.General.SuccessDialog)
                    cell(JBLabel("Sent to testers").apply {
                        font = JBUI.Fonts.label(15f).asBold()
                    })
                }

                row {
                    text(
                        "<b>${StringUtil.escapeXmlEntities(manifest.displayName)} " +
                            "${manifest.versionName} (${manifest.versionCode})</b> " +
                            "was uploaded to Firebase App Distribution and sent to $who. " +
                            "They'll get an email with a link to install it."
                    )
                }.bottomGap(BottomGap.SMALL)

                when (slackStatus) {
                    "sent" -> row {
                        icon(AllIcons.General.InspectionsOK)
                        label("Posted to Slack")
                    }

                    "failed" -> row {
                        icon(AllIcons.General.Warning)
                        text("Slack post failed: ${StringUtil.escapeXmlEntities(slackDetail)}")
                    }

                    else -> row {
                        icon(AllIcons.General.Information)
                        link("Announce releases in Slack…") {
                            ShowSettingsUtil.getInstance()
                                .showSettingsDialog(project, AppDistributionConfigurable::class.java)
                        }
                    }
                }

                if (consoleUrl != null) {
                    row {
                        cell(primaryButton("Open in Firebase Console") {
                            BrowserUtil.browse(consoleUrl)
                        }).align(AlignX.FILL)
                    }.topGap(TopGap.MEDIUM)
                }

                row {
                    button("Distribute Another Build") {
                        testers.clear()
                        showBuildStep()
                    }.align(AlignX.FILL)
                }.topGap(if (consoleUrl == null) TopGap.MEDIUM else TopGap.NONE)

                row {
                    link("Send this build to more testers") {
                        testers.clear()
                        showDistributeStep(manifest)
                    }
                }
            }

            content.border = JBUI.Borders.empty(12, 16)

            add(scrollable(content), BorderLayout.CENTER)

            revalidate()
            repaint()
        }

        // =========================================================
        // TESTERS
        // =========================================================

        private fun lastTesters(): List<String> =
            properties.getList(LAST_TESTERS_KEY).orEmpty()

        private fun recentTesters(): List<String> =
            properties.getList(RECENT_TESTERS_KEY).orEmpty()

        /** Remembered per project, so the same QA list is one click away. */
        private fun rememberTesters(used: List<String>) {
            properties.setList(LAST_TESTERS_KEY, used)
            properties.setList(
                RECENT_TESTERS_KEY,
                (used + recentTesters()).distinct().take(MAX_RECENT_TESTERS)
            )
        }

        private inner class TestersSection(
            private val onChange: () -> Unit
        ) {

            val input = JBTextField().apply {
                emptyText.text = "Tester email (paste several, comma-separated)"
            }

            val error = JBLabel().apply {
                foreground = JBColor.RED
                font = JBUI.Fonts.smallFont()
                icon = AllIcons.General.BalloonError
                isVisible = false
            }

            val list = JPanel(
                VerticalLayout(JBUI.scale(2))
            ).apply {
                isOpaque = false
            }

            /** Recent testers not currently added, one click each. */
            val suggestions = JPanel(
                WrapLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))
            ).apply {
                isOpaque = false
            }

            init {
                input.addActionListener {
                    submitInput()
                }

                input.document.addDocumentListener(
                    object : DocumentListener {
                        override fun insertUpdate(e: DocumentEvent) = clearError()
                        override fun removeUpdate(e: DocumentEvent) = clearError()
                        override fun changedUpdate(e: DocumentEvent) = clearError()
                    }
                )

                render()
            }

            fun submitInput() {
                val entries = input.text
                    .split(',', ';', ' ', '\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                if (entries.isEmpty()) {
                    return
                }

                val invalid = entries.filterNot {
                    EMAIL_REGEX.matches(it)
                }

                if (invalid.isNotEmpty()) {
                    showError("Not a valid email: ${invalid.joinToString()}")
                    return
                }

                val duplicates = entries.filter {
                    it.lowercase() in testers
                }

                entries.forEach {
                    testers.add(it.lowercase())
                }

                input.text = ""

                if (duplicates.isNotEmpty()) {
                    showError("Already added: ${duplicates.joinToString()}")
                }

                render()
                onChange()
            }

            private fun showError(message: String) {
                error.text = message
                error.isVisible = true
                input.putClientProperty("JComponent.outline", "error")
                input.repaint()
            }

            private fun clearError() {
                if (!error.isVisible) {
                    return
                }

                error.isVisible = false
                input.putClientProperty("JComponent.outline", null)
                input.repaint()
            }

            private fun render() {
                list.removeAll()

                if (testers.isEmpty()) {
                    list.add(
                        JBLabel("No testers yet. Type an email and press Enter.").apply {
                            foreground = UIUtil.getContextHelpForeground()
                            border = JBUI.Borders.empty(4, 2)
                        }
                    )
                } else {
                    testers.forEach { email ->
                        list.add(testerRow(email))
                    }
                }

                renderSuggestions()

                list.revalidate()
                list.repaint()
            }

            private fun renderSuggestions() {
                suggestions.removeAll()

                val available = recentTesters().filter { it !in testers }

                if (available.isNotEmpty()) {
                    suggestions.add(JBLabel("Recent:").apply {
                        foreground = UIUtil.getContextHelpForeground()
                    })

                    available.take(MAX_SUGGESTIONS).forEach { email ->
                        suggestions.add(ActionLink("+ $email") {
                            testers.add(email)
                            render()
                            onChange()
                        })
                    }

                    if (available.size > 1) {
                        suggestions.add(ActionLink("Add all") {
                            testers.addAll(available)
                            render()
                            onChange()
                        })
                    }
                }

                suggestions.isVisible = available.isNotEmpty()
                suggestions.revalidate()
                suggestions.repaint()
            }

            private fun testerRow(
                email: String
            ): JComponent {

                val row = JPanel(
                    BorderLayout(JBUI.scale(8), 0)
                ).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(3, 2)
                }

                row.add(
                    JBLabel(email, AllIcons.General.User, SwingConstants.LEFT),
                    BorderLayout.CENTER
                )

                row.add(
                    InplaceButton("Remove $email", AllIcons.Actions.Close) {
                        testers.remove(email)
                        render()
                        onChange()
                    },
                    BorderLayout.EAST
                )

                return row
            }
        }

        // =========================================================
        // CLI
        // =========================================================

        /**
         * Runs the App Distribution CLI in the project directory on a background
         * thread. [onLine] gets each output line on the EDT; [onDone] gets
         * the exit code and all output on the EDT.
         */
        private fun runCli(
            args: List<String>,
            task: DistributionCli.Task? = null,
            /** Computed on the background thread (may read the password store). */
            environment: () -> Map<String, String> = { emptyMap() },
            onLine: (String) -> Unit = {},
            onDone: (exitCode: Int, output: List<String>) -> Unit
        ) {

            val projectPath = project.basePath ?: run {
                onDone(-1, listOf("Android project path could not be determined."))
                return
            }

            Thread {
                val result = DistributionCli.run(
                    args = args,
                    directory = File(projectPath),
                    environment = environment(),
                    task = task,
                    onLine = { line -> SwingUtilities.invokeLater { onLine(line) } }
                )

                SwingUtilities.invokeLater {
                    onDone(result.exitCode, result.output)
                }
            }.start()
        }

        private fun parseProgress(line: String): Pair<Int, String>? =
            PROGRESS_REGEX.find(line)?.let {
                it.groupValues[1].toInt() to it.groupValues[2]
            }

        private fun hasMarker(
            output: List<String>,
            name: String
        ) = output.any { it.trim().startsWith("[$name]") }

        private fun markerValue(
            output: List<String>,
            name: String
        ): String? = output
            .firstOrNull { it.trim().startsWith("[$name]") }
            ?.trim()
            ?.removePrefix("[$name]")
            ?.trim()
            ?.ifEmpty { null }

        /** Most useful error line from CLI output, for dialogs. */
        private fun failureReason(
            output: List<String>
        ): String {

            val lines = output
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("[APPDIST_") }

            val reason = lines.lastOrNull { it.startsWith("Error:") }
                ?: lines.lastOrNull { it.startsWith("✗") }
                ?: lines.lastOrNull()
                ?: "Unknown error"

            return StringUtil.shortenTextWithEllipsis(reason.removePrefix("✗").trim(), 400, 0)
        }

        // =========================================================
        // COMPONENT HELPERS
        // =========================================================

        /** Logo, title and a one-line description of the current step. */
        private fun com.intellij.ui.dsl.builder.Panel.header(
            description: String
        ) {
            row {
                icon(AppDistributionIcons.Logo)
                cell(JBLabel(TITLE).apply {
                    font = JBUI.Fonts.label(16f).asBold()
                })
            }
            row {
                comment(description)
            }.bottomGap(BottomGap.SMALL)
        }

        /** "✓ Build  ›  2 Distribute  ›  3 Done" */
        private fun stepper(
            current: Int
        ): JComponent {

            val panel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
            }

            STEPS.forEachIndexed { index, name ->
                val number = index + 1

                if (index > 0) {
                    panel.add(JBLabel("   ›   ").apply {
                        foreground = UIUtil.getContextHelpForeground()
                    })
                }

                panel.add(JBLabel().apply {
                    when {
                        number < current -> {
                            text = name
                            icon = AllIcons.General.InspectionsOK
                            foreground = UIUtil.getContextHelpForeground()
                        }

                        number == current -> {
                            text = "$number  $name"
                            font = font.deriveFont(Font.BOLD)
                            foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
                        }

                        else -> {
                            text = "$number  $name"
                            foreground = UIUtil.getContextHelpForeground()
                        }
                    }
                })
            }

            return panel
        }

        private fun builtAgo(apk: File): String =
            "built " + DateFormatUtil.formatPrettyDateTime(apk.lastModified())
                .replaceFirstChar { it.lowercase() }

        private fun primaryButton(
            text: String,
            onClick: () -> Unit = {}
        ): JButton {

            return JButton(text).apply {
                putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
                addActionListener { onClick() }
            }
        }

        private fun copyableValue(
            text: String,
            copyText: String = text
        ): JComponent {

            val panel = JPanel(
                BorderLayout(JBUI.scale(4), 0)
            ).apply {
                isOpaque = false
            }

            panel.add(
                JBLabel(text).apply {
                    toolTipText = copyText
                    // Let long values shrink with the tool window
                    minimumSize = Dimension(0, preferredSize.height)
                },
                BorderLayout.CENTER
            )

            panel.add(
                InplaceButton("Copy", AllIcons.Actions.Copy) {
                    CopyPasteManager.getInstance()
                        .setContents(StringSelection(copyText))
                },
                BorderLayout.EAST
            )

            return panel
        }

        /**
         * Wraps content in a scroll pane that fills the viewport when there
         * is room (so resizable rows can grow) and scrolls when there isn't.
         */
        private fun scrollable(
            content: JComponent
        ): JComponent {

            val wrapper = object : JPanel(BorderLayout()), Scrollable {

                override fun getPreferredScrollableViewportSize(): Dimension =
                    preferredSize

                override fun getScrollableUnitIncrement(
                    visibleRect: Rectangle,
                    orientation: Int,
                    direction: Int
                ) = JBUI.scale(16)

                override fun getScrollableBlockIncrement(
                    visibleRect: Rectangle,
                    orientation: Int,
                    direction: Int
                ) = visibleRect.height

                override fun getScrollableTracksViewportWidth() = true

                override fun getScrollableTracksViewportHeight(): Boolean {
                    val viewport = parent as? JViewport ?: return false
                    return viewport.height > preferredSize.height
                }
            }

            wrapper.add(content, BorderLayout.CENTER)

            return JBScrollPane(wrapper).apply {
                border = JBUI.Borders.empty()
            }
        }

        // =========================================================
        // RELEASE MANIFEST
        // =========================================================

        /**
         * Details of the last release build, written by `appdist release` to
         * <app module>/build/app-distribution/release.json.
         */
        private fun loadReleaseManifest(): ReleaseManifest? {
            val file = findReleaseManifestFile() ?: return null

            return try {
                val json = JsonParser.parseString(file.readText()).asJsonObject

                ReleaseManifest(
                    projectPath = json["projectPath"].asString,
                    apkPath = json["apkPath"].asString,
                    applicationId = json["applicationId"].asString,
                    versionName = json["versionName"].asString,
                    versionCode = json["versionCode"].asInt,
                    appName = json["appName"]?.asString.orEmpty()
                )
            } catch (e: Exception) {
                null
            }
        }

        /** Path reported by the last build, else a search of the project's modules. */
        private fun findReleaseManifestFile(): File? {
            properties.getValue(LAST_MANIFEST_KEY)
                ?.let(::File)
                ?.takeIf { it.isFile }
                ?.let { return it }

            val projectPath = project.basePath ?: return null

            return File(projectPath)
                .walkTopDown()
                .maxDepth(4)
                .onEnter { !it.name.startsWith(".") && it.name != "node_modules" }
                .filter { it.isFile && it.name == "release.json" && it.parentFile.name == "app-distribution" }
                .maxByOrNull { it.lastModified() }
        }
    }

    private data class ReleaseManifest(
        val projectPath: String,
        val apkPath: String,
        val applicationId: String,
        val versionName: String,
        val versionCode: Int,
        /** App label from the APK; empty if unknown. */
        val appName: String = ""
    ) {
        val displayName: String
            get() = appName.ifEmpty { applicationId }
    }

    private companion object {
        val STEPS = listOf("Build", "Distribute", "Done")

        val EMAIL_REGEX = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")

        val PROGRESS_REGEX = Regex("""\[APPDIST_PROGRESS]\s*(\d+)\|(.*)""")

        /** Release notes limit. */
        const val MAX_RELEASE_NOTES = 4_000

        const val LAST_MANIFEST_KEY = "appdist.lastManifest"
        const val LAST_TESTERS_KEY = "appdist.lastTesters"
        const val RECENT_TESTERS_KEY = "appdist.recentTesters"
        const val MAX_RECENT_TESTERS = 30
        const val MAX_SUGGESTIONS = 6
    }
}
