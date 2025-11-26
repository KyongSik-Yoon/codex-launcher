package com.github.x0x0b.codexlauncher.files

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.Change
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.github.x0x0b.codexlauncher.settings.CodexLauncherSettings
import com.github.x0x0b.codexlauncher.diff.CodexChangeSnapshotService
import com.github.x0x0b.codexlauncher.diff.CodexPreviewStateService
import com.intellij.openapi.vcs.changes.InvokeAfterUpdateMode
import com.intellij.openapi.diagnostic.logger

/**
 * Service responsible for monitoring file changes and automatically opening them in the editor.
 * Tracks both version-controlled and untracked files within the project scope.
 */

@Service(Service.Level.PROJECT)
class FileOpenService(private val project: Project) : Disposable {
    
    private val logger = logger<FileOpenService>()
    // Track timing for file modification detection (project-specific)
    private var lastRefreshTime: Long = System.currentTimeMillis()
    
    companion object {
        /** Time to wait for VCS update detection (in milliseconds) */
        private const val VCS_UPDATE_WAIT_MS = 1500L

        /** Time to buffer after last /refresh call to ensure file timestamps are updated */
        private const val REFRESH_BUFFER_MS = 1000L
    }
    
    /**
     * Updates the last refresh time for this project.
     */
    fun updateLastRefreshTime() {
        lastRefreshTime = System.currentTimeMillis()
    }

    /**
     * Processes recently changed files and opens them in the editor if configured to do so.
     * This includes both tracked (version-controlled) and untracked (new) files.
     */
    fun processChangedFilesAndOpen() {
        val changeListManager = ChangeListManager.getInstance(project)
        changeListManager.invokeAfterUpdate({
            val thresholdTime = calculateThresholdTime()
            waitForVcsUpdate()
            
            val filesToOpen = mutableSetOf<VirtualFile>()
            collectTrackedChangedFiles(changeListManager, thresholdTime, filesToOpen)
            collectUntrackedFiles(changeListManager, thresholdTime, filesToOpen)
            openCollectedFiles(filesToOpen)
        }, InvokeAfterUpdateMode.SYNCHRONOUS_NOT_CANCELLABLE, null, null)
    }
    
    /**
     * Calculates the timestamp threshold for determining recently modified files.
     * Uses the latter of service startup time or the last /refresh call time for this project.
     * Files created/modified after this time will be opened.
     */
    private fun calculateThresholdTime(): Long {
        return lastRefreshTime + REFRESH_BUFFER_MS
    }
    
    /**
     * Waits for VCS operations to complete to ensure all changes are detected.
     */
    private fun waitForVcsUpdate() {
        try {
            Thread.sleep(VCS_UPDATE_WAIT_MS)
        } catch (e: InterruptedException) {
            logger.warn("VCS update wait was interrupted", e)
            Thread.currentThread().interrupt()
        }
    }
    
    /**
     * Collects version-controlled files that have been recently modified.
     */
    private fun collectTrackedChangedFiles(
        changeListManager: ChangeListManager,
        thresholdTime: Long,
        filesToOpen: MutableSet<VirtualFile>
    ) {
        val allChanges = changeListManager.allChanges
        for (change in allChanges) {
            val virtualFile = change.afterRevision?.file?.virtualFile
                ?: change.beforeRevision?.file?.virtualFile

            virtualFile?.let { file ->
                if (isRecentlyModifiedProjectFile(file, thresholdTime)) {
                    filesToOpen.add(file)
                }
            }
        }
    }
    
    /**
     * Collects untracked (new) files that have been recently created or modified.
     */
    private fun collectUntrackedFiles(
        changeListManager: ChangeListManager,
        thresholdTime: Long,
        filesToOpen: MutableSet<VirtualFile>
    ) {
        val untrackedFilePaths = changeListManager.unversionedFilesPaths
        for (untrackedPath in untrackedFilePaths) {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(untrackedPath.toString())
            virtualFile?.let { file ->
                if (isRecentlyModifiedProjectFile(file, thresholdTime)) {
                    filesToOpen.add(file)
                }
            }
        }
    }
    
    /**
     * Opens all collected files in the editor.
     */
    private fun openCollectedFiles(filesToOpen: Set<VirtualFile>) {
        val changeListManager = ChangeListManager.getInstance(project)
        val settings = service<CodexLauncherSettings>()
        val snapshotService = project.service<CodexChangeSnapshotService>()
        val previewState = project.service<CodexPreviewStateService>()

        val openDiff = settings.state.openDiffOnChange
        val openFile = settings.state.openFileOnChange

        logger.info("CodexLauncher: processing ${filesToOpen.size} changed files (openDiff=$openDiff, openFile=$openFile)")

        for (file in filesToOpen) {
            val change = changeListManager.getChange(file)

            // Record snapshot for potential revert-before/after Codex review
            snapshotService.recordSnapshot(change, file)

            if (openDiff && change != null) {
                // If a Codex preview diff was already shown for this file, skip the
                // follow-up VCS diff after /refresh to avoid duplicate popups.
                if (!previewState.consumePreviewShown(file.path)) {
                    showDiff(change)
                } else {
                    logger.info("CodexLauncher: skipping VCS diff for ${file.path} (Codex preview already shown)")
                }
            }
            if (openFile) {
                openFileInEditor(file)
            }
        }
    }
    
    /**
     * Checks if the file is a project file that has been recently modified.
     */
    private fun isRecentlyModifiedProjectFile(file: VirtualFile, thresholdTime: Long): Boolean {
        return isProjectFile(file.path) && !file.isDirectory && file.timeStamp >= thresholdTime
    }

    private fun isProjectFile(filePath: String): Boolean {
        val projectBasePath = project.basePath ?: return false
        return filePath.startsWith(projectBasePath) && !filePath.endsWith("/")
    }

    private fun openFileInEditor(file: VirtualFile) {
        try {
            val settings = service<CodexLauncherSettings>()
            if (!settings.state.openFileOnChange) {
                return
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    logger.debug("Project disposed, skipping file open for: ${file.path}")
                    return@invokeLater
                }
                try {
                    FileEditorManager.getInstance(project).openFile(file, true)
                    logger.debug("Opened file in editor: ${file.path}")
                } catch (e: Exception) {
                    logger.error("Failed to open file in editor: ${file.path}", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Error in openFileInEditor for file: ${file.path}", e)
        }
    }

    private fun showDiff(change: Change) {
        try {
            val settings = service<CodexLauncherSettings>()
            if (!settings.state.openDiffOnChange) {
                return
            }

            val projectRef = project
            if (projectRef.isDisposed) {
                logger.debug("Project disposed, skipping diff view for change: ${change}")
                return
            }

            val filePath = change.afterRevision?.file?.path
                ?: change.beforeRevision?.file?.path
                ?: ""
            logger.info("CodexLauncher: preparing diff view for change in '$filePath'")

            ApplicationManager.getApplication().invokeLater {
                if (projectRef.isDisposed) {
                    return@invokeLater
                }
                try {
                    val beforeText = change.beforeRevision?.content ?: ""
                    val afterText = change.afterRevision?.content ?: ""

                    val diffContentFactory = DiffContentFactory.getInstance()
                    val beforeContent = diffContentFactory.create(projectRef, beforeText)
                    val afterContent = diffContentFactory.create(projectRef, afterText)

                    val title = if (filePath.isNotEmpty()) {
                        "Changes in $filePath"
                    } else {
                        "Changes"
                    }

                    val request = SimpleDiffRequest(
                        title,
                        beforeContent,
                        afterContent,
                        "Before",
                        "After"
                    )

                    DiffManager.getInstance().showDiff(projectRef, request)
                } catch (e: Exception) {
                    logger.warn("Failed to show diff for change: ${change}", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Error while preparing diff view for change: ${change}", e)
        }
    }

    override fun dispose() {
    }
}
