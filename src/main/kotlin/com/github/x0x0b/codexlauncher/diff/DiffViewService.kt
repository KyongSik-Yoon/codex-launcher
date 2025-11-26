package com.github.x0x0b.codexlauncher.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Paths

/**
 * Project-level service for showing Codex suggestions as diff views
 * without modifying files on disk.
 *
 * This is intentionally lightweight and transport-agnostic: callers
 * simply provide an absolute file path and the suggested content.
 */
@Service(Service.Level.PROJECT)
class DiffViewService(private val project: Project) : Disposable {

    private val logger = logger<DiffViewService>()

    /**
     * Shows a diff view comparing the current file content (if any)
     * with [suggestedContent].
     *
     * - Left: current on-disk content (or empty for new files)
     * - Right: suggested content (editable)
     */
    fun showSuggestionDiff(filePath: String, suggestedContent: String) {
        // Delegate to the snapshot-aware overload without an explicit original snapshot.
        showSuggestionDiff(filePath, null, suggestedContent)
    }

    /**
     * Shows a diff view comparing [originalContent] (when provided) or the current
     * on-disk file contents with [suggestedContent].
     *
     * This overload is used by Codex preview flows to ensure the "Original" side
     * remains a stable snapshot even if the underlying file is modified afterwards
     * (e.g. when the user accepts the suggested changes in Codex).
     */
    fun showSuggestionDiff(filePath: String, originalContent: String?, suggestedContent: String) {
        if (project.isDisposed) {
            logger.debug("Project is disposed, skipping diff for: $filePath")
            return
        }

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                logger.debug("Project is disposed on EDT, skipping diff for: $filePath")
                return@invokeLater
            }

            try {
                val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                val diffContentFactory = DiffContentFactory.getInstance()

                val leftContent =
                    if (originalContent != null) {
                        // Use snapshot text for the original side (no live updates).
                        diffContentFactory.create(originalContent)
                    } else if (vFile != null) {
                        // Fallback: live view of the current file contents.
                        diffContentFactory.create(project, vFile)
                    } else {
                        // New file – original is empty
                        diffContentFactory.create("")
                    }

                val rightContent = diffContentFactory.createEditable(
                    project,
                    suggestedContent,
                    vFile?.fileType
                )

                val fileName = try {
                    Paths.get(filePath).fileName?.toString() ?: filePath
                } catch (e: Exception) {
                    filePath
                }

                val title = "$fileName ↔ Suggested"

                val request = SimpleDiffRequest(
                    title,
                    leftContent,
                    rightContent,
                    "Original",
                    "Codex suggestion"
                )

                // Prefer focusing the suggestion side, keep original read-only
                request.putUserData(DiffUserDataKeys.MASTER_SIDE, Side.RIGHT)
                request.putUserData(DiffUserDataKeys.PREFERRED_FOCUS_SIDE, Side.RIGHT)
                request.putUserData(
                    DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS,
                    booleanArrayOf(true, false)
                )

                DiffManager.getInstance().showDiff(project, request)
            } catch (t: Throwable) {
                logger.warn("Failed to show suggestion diff for: $filePath", t)
            }
        }
    }

    /**
     * Shows a diff view for an arbitrary text snippet.
     *
     * - Left: [originalSnippet]
     * - Right: [suggestedSnippet] (editable)
     */
    fun showSnippetDiff(title: String, originalSnippet: String, suggestedSnippet: String) {
        if (project.isDisposed) {
            logger.debug("Project is disposed, skipping snippet diff: $title")
            return
        }

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                logger.debug("Project is disposed on EDT, skipping snippet diff: $title")
                return@invokeLater
            }

            try {
                val diffContentFactory = DiffContentFactory.getInstance()
                val leftContent = diffContentFactory.create(originalSnippet)
                val rightContent = diffContentFactory.createEditable(project, suggestedSnippet, null)

                val request = SimpleDiffRequest(
                    title,
                    leftContent,
                    rightContent,
                    "Original",
                    "Codex suggestion"
                )

                request.putUserData(DiffUserDataKeys.MASTER_SIDE, Side.RIGHT)
                request.putUserData(DiffUserDataKeys.PREFERRED_FOCUS_SIDE, Side.RIGHT)
                request.putUserData(
                    DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS,
                    booleanArrayOf(true, false)
                )

                DiffManager.getInstance().showDiff(project, request)
            } catch (t: Throwable) {
                logger.warn("Failed to show snippet diff: $title", t)
            }
        }
    }

    override fun dispose() {
        // No-op; kept for symmetry with other services.
    }
}
