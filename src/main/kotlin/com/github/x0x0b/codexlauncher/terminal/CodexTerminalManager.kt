package com.github.x0x0b.codexlauncher.terminal

import com.github.x0x0b.codexlauncher.diff.DiffViewService
import com.github.x0x0b.codexlauncher.diff.CodexPreviewStateService
import com.github.x0x0b.codexlauncher.diff.CodexOutputParser
import com.github.x0x0b.codexlauncher.diff.CodexPreview
import com.github.x0x0b.codexlauncher.diff.CodexEdit
import com.github.x0x0b.codexlauncher.diff.CodexLineType
import com.github.x0x0b.codexlauncher.settings.CodexLauncherSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import com.jediterm.terminal.model.TerminalTextBuffer
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.function.Function

/**
 * Project-level service responsible for managing Codex terminals.
 * Encapsulates lookup, reuse, focus, and command execution logic so actions stay thin.
 */
@Service(Service.Level.PROJECT)
class CodexTerminalManager(private val project: Project) {

    companion object {
        private val CODEX_TERMINAL_KEY = Key.create<Boolean>("codex.launcher.codexTerminal")
        private val CODEX_TERMINAL_RUNNING_KEY = Key.create<Boolean>("codex.launcher.codexTerminal.running")
        private val CODEX_TERMINAL_CALLBACK_KEY = Key.create<Boolean>("codex.launcher.codexTerminal.callbackRegistered")
    }

    private val logger = logger<CodexTerminalManager>()

    private data class CodexTerminal(val widget: TerminalWidget, val content: Content)

    // Monitor scheduled for the currently running Codex terminal (at most one per project)
    @Volatile
    private var patchMonitorFuture: ScheduledFuture<*>? = null

    // Tracks the last processed Codex preview "tail" (hash of the buffer text starting
    // at the preview marker). This is used to avoid opening duplicate diff views while
    // the buffer content remains unchanged.
    @Volatile
    private var lastPreviewFingerprint: Int? = null

    /**
     * Launches or reuses the Codex terminal for the given command.
     * @throws Throwable when terminal creation or command execution fails.
     */
    fun launch(baseDir: String, command: String) {
        val terminalManager = TerminalToolWindowManager.getInstance(project)
        var existingTerminal = locateCodexTerminal(terminalManager)

        existingTerminal?.let { terminal ->
            ensureTerminationCallback(terminal.widget, terminal.content)
            if (isCodexRunning(terminal)) {
                logger.info("Focusing active Codex terminal")
                focusCodexTerminal(terminalManager, terminal)
                return
            }

            if (reuseCodexTerminal(terminal, command)) {
                logger.info("Reused existing Codex terminal for new Codex run")
                focusCodexTerminal(terminalManager, terminal)
                return
            } else {
                clearCodexMetadata(terminalManager, terminal.widget)
                existingTerminal = null
            }
        }

        var widget: TerminalWidget? = null
        try {
            widget = terminalManager.createShellWidget(baseDir, "Codex", true, true)
            val content = markCodexTerminal(terminalManager, widget)
            if (!sendCommandToTerminal(widget, content, command)) {
                throw IllegalStateException("Failed to execute Codex command")
            }
            // Start monitoring Codex output for patch suggestions when diff preview is enabled.
            val settings = ApplicationManager.getApplication().getService(CodexLauncherSettings::class.java)
            if (settings.state.openDiffOnChange) {
                startPatchMonitor(widget, baseDir)
            }
            if (content != null) {
                focusCodexTerminal(terminalManager, CodexTerminal(widget, content))
            }
        } catch (sendError: Throwable) {
            widget?.let { clearCodexMetadata(terminalManager, it) }
            throw sendError
        }
    }

    /**
     * Returns true when the Codex terminal tab is currently selected in the terminal tool window.
     */
    fun isCodexTerminalActive(): Boolean {
        return try {
            val terminalManager = TerminalToolWindowManager.getInstance(project)
            findDisplayedCodexTerminal(terminalManager) != null
        } catch (t: Throwable) {
            logger.warn("Failed to inspect Codex terminal active state", t)
            false
        }
    }

    fun typeIntoActiveCodexTerminal(text: String): Boolean {
        return try {
            val terminalManager = TerminalToolWindowManager.getInstance(project)
            val terminal = findDisplayedCodexTerminal(terminalManager) ?: return false
            typeText(terminal.widget, text)
        } catch (t: Throwable) {
            logger.warn("Failed to type into Codex terminal", t)
            false
        }
    }

    private fun locateCodexTerminal(manager: TerminalToolWindowManager): CodexTerminal? = try {
        manager.terminalWidgets.asSequence().mapNotNull { widget ->
            val content = manager.getContainer(widget)?.content ?: return@mapNotNull null
            val isCodex = content.getUserData(CODEX_TERMINAL_KEY) == true || content.displayName == "Codex"
            if (!isCodex) {
                return@mapNotNull null
            }
            CodexTerminal(widget, content)
        }.firstOrNull()
    } catch (t: Throwable) {
        logger.warn("Failed to inspect existing terminal widgets", t)
        null
    }

    private fun findDisplayedCodexTerminal(
        manager: TerminalToolWindowManager
    ): CodexTerminal? {
        val terminal = locateCodexTerminal(manager) ?: return null
        val toolWindow = resolveTerminalToolWindow(manager) ?: return null
        val selectedContent = toolWindow.contentManager.selectedContent ?: return null
        if (selectedContent != terminal.content) {
            return null
        }

        val isDisplayed = toolWindow.isVisible
        if (!isDisplayed) {
            return null
        }

        return terminal
    }

    private fun focusCodexTerminal(
        manager: TerminalToolWindowManager,
        terminal: CodexTerminal
    ) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }

            try {
                val toolWindow = resolveTerminalToolWindow(manager)
                if (toolWindow == null) {
                    logger.warn("Terminal tool window is not available for focusing Codex")
                    return@invokeLater
                }

                val contentManager = toolWindow.contentManager
                if (contentManager.selectedContent != terminal.content) {
                    contentManager.setSelectedContent(terminal.content, true)
                }

                toolWindow.activate({
                    try {
                        terminal.widget.requestFocus()
                    } catch (focusError: Throwable) {
                        logger.warn("Failed to request focus for Codex terminal", focusError)
                    }
                }, true)
            } catch (focusError: Throwable) {
                logger.warn("Failed to focus existing Codex terminal", focusError)
            }
        }
    }

    private fun resolveTerminalToolWindow(
        manager: TerminalToolWindowManager
    ) = manager.getToolWindow()
        ?: ToolWindowManager.getInstance(project)
            .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)

    private fun markCodexTerminal(manager: TerminalToolWindowManager, widget: TerminalWidget): Content? {
        return try {
            manager.getContainer(widget)?.content?.also { content ->
                content.putUserData(CODEX_TERMINAL_KEY, true)
                setCodexRunning(content, false)
                ensureTerminationCallback(widget, content)
                content.displayName = "Codex"
            }
        } catch (t: Throwable) {
            logger.warn("Failed to tag Codex terminal metadata", t)
            null
        }
    }

    private fun clearCodexMetadata(manager: TerminalToolWindowManager, widget: TerminalWidget) {
        try {
            manager.getContainer(widget)?.content?.let { content ->
                clearCodexMetadata(content)
            }
        } catch (t: Throwable) {
            logger.warn("Failed to clear Codex terminal metadata", t)
        }
    }

    private fun clearCodexMetadata(content: Content) {
        content.putUserData(CODEX_TERMINAL_KEY, null)
        content.putUserData(CODEX_TERMINAL_RUNNING_KEY, null)
        content.putUserData(CODEX_TERMINAL_CALLBACK_KEY, null)
    }

    private fun reuseCodexTerminal(
        terminal: CodexTerminal,
        command: String
    ): Boolean {
        ensureTerminationCallback(terminal.widget, terminal.content)
        return sendCommandToTerminal(terminal.widget, terminal.content, command)
    }

    private fun sendCommandToTerminal(
        widget: TerminalWidget,
        content: Content?,
        command: String
    ): Boolean {
        return try {
            widget.sendCommandToExecute(command)
            setCodexRunning(content, true)
            true
        } catch (t: Throwable) {
            logger.warn("Failed to execute Codex command", t)
            setCodexRunning(content, false)
            false
        }
    }

    private fun isCodexRunning(terminal: CodexTerminal): Boolean {
        val liveState = invokeIsCommandRunning(terminal.widget)
        if (liveState != null) {
            setCodexRunning(terminal.content, liveState)
            return liveState
        }
        return terminal.content.getUserData(CODEX_TERMINAL_RUNNING_KEY) ?: false
    }

    private fun setCodexRunning(content: Content?, running: Boolean) {
        content?.putUserData(CODEX_TERMINAL_RUNNING_KEY, running)
    }

    private fun ensureTerminationCallback(widget: TerminalWidget, content: Content?) {
        if (content == null) return
        if (content.getUserData(CODEX_TERMINAL_CALLBACK_KEY) == true) return
        try {
            widget.addTerminationCallback({ setCodexRunning(content, false) }, content)
            content.putUserData(CODEX_TERMINAL_CALLBACK_KEY, true)
        } catch (t: Throwable) {
            logger.warn("Failed to register termination callback", t)
        }
    }

    private fun invokeIsCommandRunning(widget: TerminalWidget): Boolean? {
        return runCatching {
            val method = widget.javaClass.methods.firstOrNull { it.name == "isCommandRunning" && it.parameterCount == 0 }
            method?.apply { isAccessible = true }?.invoke(widget) as? Boolean
        }.getOrNull()
    }

    /**
     * Starts a lightweight polling task that inspects the Codex terminal buffer for
     * unified diff output while Codex is running. When a new diff is detected, an IDE
     * diff view is opened to preview the suggested changes.
     *
     * This is a best-effort, heuristic-based implementation intended for local use.
     */
    private fun startPatchMonitor(widget: TerminalWidget, baseDir: String) {
        // Cancel any existing monitor
        patchMonitorFuture?.cancel(false)
        lastPreviewFingerprint = null

        val shellWidget = ShellTerminalWidget.asShellJediTermWidget(widget)
        if (shellWidget == null) {
            logger.warn("CodexLauncher: ShellTerminalWidget adapter is not available; skipping patch monitor")
            return
        }

        val executor = AppExecutorUtil.getAppScheduledExecutorService()
        patchMonitorFuture = executor.scheduleWithFixedDelay({
            try {
                if (project.isDisposed) {
                    patchMonitorFuture?.cancel(false)
                    return@scheduleWithFixedDelay
                }

                val text = readTerminalBuffer(shellWidget) ?: return@scheduleWithFixedDelay
                if (text.isBlank()) return@scheduleWithFixedDelay

                val marker = "Would you like to make the following edits?"
                val offset = text.lastIndexOf(marker)
                if (offset == -1) return@scheduleWithFixedDelay

                val tail = text.substring(offset)

                // Avoid reopening the same preview repeatedly while the buffer text is static.
                val fingerprint = tail.hashCode()
                if (lastPreviewFingerprint == fingerprint) {
                    return@scheduleWithFixedDelay
                }
                lastPreviewFingerprint = fingerprint

                val preview = CodexOutputParser.parsePreview(text) ?: return@scheduleWithFixedDelay

                logger.info("CodexLauncher: detected Codex preview diff, opening preview")
                openDiffPreviewFromSummary(preview)
            } catch (t: Throwable) {
                logger.warn("CodexLauncher: patch monitor iteration failed", t)
            }
        }, 1500, 1500, TimeUnit.MILLISECONDS)
    }

    /**
     * Uses reflection to access ShellTerminalWidget.processTerminalBuffer, which is
     * package-private in the terminal plugin. This is intentionally best-effort and
     * may break with future IDE versions.
     */
    private fun readTerminalBuffer(shellWidget: ShellTerminalWidget): String? {
        return runCatching {
            val method = shellWidget.javaClass.getDeclaredMethod(
                "processTerminalBuffer",
                Function::class.java
            )
            method.isAccessible = true
            val fn = Function { bufferObj: Any ->
                val buffer = bufferObj as TerminalTextBuffer
                buildString {
                    val history = buffer.historyLinesStorage
                    for (i in 0 until history.size) {
                        append(history[i].text)
                        append('\n')
                    }
                    val screen = buffer.screenLinesStorage
                    for (i in 0 until screen.size) {
                        append(screen[i].text)
                        append('\n')
                    }
                }
            }
            method.invoke(shellWidget, fn) as? String
        }.getOrNull()
    }

    /**
     * Attempts to construct a full-file suggestion from a Codex preview by applying
     * [CodexEdit] entries to the current file contents.
     *
     * IMPORTANT FORMAT ASSUMPTIONS (MIRRORED FROM [CodexEdit]):
     * - [CodexEdit.lineNumber] refers to the 1-based line index in the *current* file
     *   contents at the time Codex produced the preview.
     * - For each line number, edits are interpreted in this order:
     *   - CONTEXT: the line exists and is unchanged.
     *   - DELETE: the line exists and is removed.
     *   - ADD: a new line is inserted at the given index after applying prior edits.
     *
     * The implementation uses a simple delta-based patching strategy:
     *  - Lines are stored as a mutable list.
     *  - Edits are sorted by (lineNumber, typeOrder) with DELETE before CONTEXT before ADD.
     *  - An "offset" tracks how insertions/removals shift subsequent indices.
     *
     * If any index is out of bounds or a CONTEXT line does not match the current file
     * (ignoring trailing whitespace), the method aborts and falls back to snippet diff.
     */
    private fun openDiffPreviewFromSummary(preview: CodexPreview) {
        try {
            val diffService = project.getService(DiffViewService::class.java)

            val projectBase = project.basePath
            val normalizedPath = stripPrefixAndNormalizePath(preview.filePath)
            val absolutePath = if (projectBase != null) {
                java.nio.file.Paths.get(projectBase, normalizedPath).toString()
            } else {
                normalizedPath
            }

            val vFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(absolutePath)

            val previewState = project.getService(CodexPreviewStateService::class.java)

            if (vFile != null && vFile.isValid && preview.edits.isNotEmpty()) {
                val fullText = com.intellij.openapi.vfs.VfsUtil.loadText(vFile)
                val suggestedFull = applyPreviewToFullFile(fullText, preview)
                if (suggestedFull != null && suggestedFull != fullText) {
                    logger.info("CodexLauncher: showing full-file diff for ${preview.filePath}")
                    previewState.markPreviewShown(vFile.path)
                    // Pass fullText as a snapshot so diff doesn't collapse to "identical"
                    // after the underlying file is modified by Codex.
                    diffService.showSuggestionDiff(vFile.path, fullText, suggestedFull)
                    return
                } else {
                    logger.info(
                        "CodexLauncher: full-file patch application produced no changes or failed " +
                            "for ${preview.filePath}, falling back to snippet diff"
                    )
                }
            }

            // Fallback: show only the snippet diff (still mark preview to avoid double VCS diff).
            if (vFile != null) {
                previewState.markPreviewShown(vFile.path)
            }
            val title = "${preview.filePath} (Codex preview)"
            diffService.showSnippetDiff(title, preview.originalSnippet, preview.suggestedSnippet)
        } catch (t: Throwable) {
            logger.warn("CodexLauncher: failed to open snippet diff preview", t)
        }
    }

    private fun applyPreviewToFullFile(
        fullText: String,
        preview: CodexPreview
    ): String? {
        val edits = preview.edits
        if (edits.isEmpty()) return null

        val lines = fullText.split('\n').toMutableList()
        var offset = 0

        fun typeOrder(type: CodexLineType): Int =
            when (type) {
                CodexLineType.DELETE -> 0
                CodexLineType.CONTEXT -> 1
                CodexLineType.ADD -> 2
            }

        val sorted = edits.sortedWith(
            compareBy<CodexEdit> { it.lineNumber }.thenBy { typeOrder(it.type) }
        )

        for (edit in sorted) {
            val targetIndex = edit.lineNumber - 1 + offset
            if (targetIndex < 0 || targetIndex > lines.size) {
                logger.warn(
                    "CodexLauncher: target index out of bounds while applying preview " +
                        "(${edit.lineNumber} -> $targetIndex, size=${lines.size})"
                )
                return null
            }

            when (edit.type) {
                CodexLineType.CONTEXT -> {
                    if (targetIndex >= lines.size) {
                        logger.warn(
                            "CodexLauncher: CONTEXT line out of bounds at $targetIndex " +
                                "for ${preview.filePath}"
                        )
                        return null
                    }
                    val current = lines[targetIndex]
                    val expected = edit.text
                    // We only use CONTEXT lines as a soft sanity check.
                    // Whitespace differences (indentation, trailing spaces) are ignored,
                    // because the Codex preview often normalizes or reflows them.
                    if (expected.isNotBlank() &&
                        current.trim() != expected.trim()
                    ) {
                        logger.info(
                            "CodexLauncher: CONTEXT mismatch at line ${edit.lineNumber} " +
                                "for ${preview.filePath}: expected='${expected.trim()}', actual='${current.trim()}'"
                        )
                        // Do not abort – continue applying the patch best-effort.
                    }
                }
                CodexLineType.DELETE -> {
                    if (targetIndex >= lines.size) {
                        logger.warn(
                            "CodexLauncher: DELETE line out of bounds at $targetIndex " +
                                "for ${preview.filePath}"
                        )
                        return null
                    }
                    lines.removeAt(targetIndex)
                    offset--
                }
                CodexLineType.ADD -> {
                    lines.add(targetIndex, edit.text)
                    offset++
                }
            }
        }

        return lines.joinToString("\n")
    }

    private fun stripPrefixAndNormalizePath(p: String): String {
        val s = p.removePrefix("a/").removePrefix("b/")
        val cleaned = if (s.startsWith("./")) s.substring(2) else s
        return cleaned.replace("\\", "/")
    }

    private fun typeText(widget: TerminalWidget, text: String): Boolean {
        val connector = runCatching { widget.ttyConnector }.getOrNull()
        if (connector != null) {
            return runCatching {
                connector.write(text)
                true
            }.getOrElse {
                logger.warn("Failed to write to Codex terminal connector", it)
                false
            }
        }

        val methods = widget.javaClass.methods
        val typeMethod = methods.firstOrNull { it.name == "typeText" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
        if (typeMethod != null) {
            return runCatching {
                typeMethod.isAccessible = true
                typeMethod.invoke(widget, text)
                true
            }.getOrElse {
                logger.warn("Failed to invoke typeText on Codex terminal", it)
                false
            }
        }

        val pasteMethod = methods.firstOrNull { it.name == "pasteText" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
        if (pasteMethod != null) {
            return runCatching {
                pasteMethod.isAccessible = true
                pasteMethod.invoke(widget, text)
                true
            }.getOrElse {
                logger.warn("Failed to invoke pasteText on Codex terminal", it)
                false
            }
        }

        return false
    }
}
