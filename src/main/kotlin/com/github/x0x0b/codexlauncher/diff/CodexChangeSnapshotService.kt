package com.github.x0x0b.codexlauncher.diff

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vcs.changes.Change

/**
 * Tracks before/after snapshots for files modified during the latest Codex run.
 *
 * This enables a lightweight "revert Codex changes" flow:
 * - After Codex completes and /refresh is delivered, we capture snapshots
 *   for all detected changes.
 * - The user can then revert the current file back to its pre-run content
 *   using the stored snapshot.
 */
@Service(Service.Level.PROJECT)
class CodexChangeSnapshotService(private val project: Project) : Disposable {

    data class Snapshot(
        val filePath: String,
        val originalText: String?, // null for new files
        val newText: String
    )

    private val logger = logger<CodexChangeSnapshotService>()
    private val snapshots = mutableMapOf<String, Snapshot>()

    /**
     * Records a snapshot for a changed or new file.
     *
     * @param change VCS change information, if available (null for untracked files)
     * @param file The current VirtualFile on disk (after Codex has modified it)
     */
    fun recordSnapshot(change: Change?, file: VirtualFile) {
        if (project.isDisposed) return

        try {
            val (originalText, newText) = ApplicationManager.getApplication().runReadAction<Pair<String?, String>> {
                val currentText = VfsUtil.loadText(file)
                val before = change?.beforeRevision?.content
                before to currentText
            }

            val snapshot = Snapshot(
                filePath = file.path,
                originalText = originalText,
                newText = newText
            )
            snapshots[file.path] = snapshot
            logger.info("CodexLauncher: recorded snapshot for ${file.path} (original=${originalText != null})")
        } catch (t: Throwable) {
            logger.warn("CodexLauncher: failed to record snapshot for ${file.path}", t)
        }
    }

    fun getSnapshot(file: VirtualFile): Snapshot? = snapshots[file.path]

    /**
     * Reverts the given file to the originalText stored in the snapshot.
     * For new files (originalText == null) the file will be deleted.
     */
    fun revertFileToSnapshot(file: VirtualFile) {
        if (project.isDisposed) return

        val snapshot = snapshots[file.path]
        if (snapshot == null) {
            logger.info("CodexLauncher: no snapshot for ${file.path}, nothing to revert")
            return
        }

        WriteCommandAction.runWriteCommandAction(project, "Revert Codex Changes", null, Runnable {
            try {
                if (!file.isValid) {
                    logger.warn("CodexLauncher: file is no longer valid, cannot revert: ${file.path}")
                    return@Runnable
                }

                if (snapshot.originalText == null) {
                    // File did not exist before Codex run – delete it.
                    file.delete(this)
                    logger.info("CodexLauncher: deleted new file created by Codex: ${file.path}")
                } else {
                    VfsUtil.saveText(file, snapshot.originalText)
                    logger.info("CodexLauncher: reverted file to snapshot: ${file.path}")
                }
            } catch (t: Throwable) {
                logger.warn("CodexLauncher: failed to revert file to snapshot: ${file.path}", t)
            }
        })
    }

    override fun dispose() {
        snapshots.clear()
    }
}

