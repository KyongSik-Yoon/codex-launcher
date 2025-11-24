package com.github.x0x0b.codexlauncher.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.application.ApplicationManager
import com.github.x0x0b.codexlauncher.settings.CodexLauncherSettings
import com.intellij.openapi.components.service

/**
 * Service responsible for capturing file snapshots and displaying diffs
 * when Codex makes changes to files.
 */
@Service(Service.Level.PROJECT)
class DiffViewerService(private val project: Project) : Disposable {
    
    private val logger = logger<DiffViewerService>()
    
    // Map to store original file content: file path -> content
    private val fileSnapshots = mutableMapOf<String, String>()
    
    /**
     * Captures the current state of the specified files.
     * Should be called before Codex runs.
     * @param files List of files to capture snapshots for
     */
    fun captureFileSnapshots(files: List<VirtualFile>) {
        ApplicationManager.getApplication().runReadAction {
            fileSnapshots.clear()
            for (file in files) {
                try {
                    if (!file.isDirectory && file.isValid) {
                        val content = String(file.contentsToByteArray(), file.charset)
                        fileSnapshots[file.path] = content
                        logger.debug("Captured snapshot for: ${file.path}")
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to capture snapshot for ${file.path}", e)
                }
            }
        }
    }
    
    /**
     * Captures snapshot of a single file.
     */
    fun captureFileSnapshot(file: VirtualFile) {
        ApplicationManager.getApplication().runReadAction {
            try {
                if (!file.isDirectory && file.isValid) {
                    val content = String(file.contentsToByteArray(), file.charset)
                    fileSnapshots[file.path] = content
                    logger.debug("Captured snapshot for: ${file.path}")
                }
            } catch (e: Exception) {
                logger.warn("Failed to capture snapshot for ${file.path}", e)
            }
        }
    }
    
    /**
     * Shows diff for files that have been modified since the snapshot was taken.
     * @param modifiedFiles List of files that have been modified
     */
    fun showDiffsForModifiedFiles(modifiedFiles: List<VirtualFile>) {
        val settings = service<CodexLauncherSettings>()
        if (!settings.state.showDiffOnChange) {
            logger.debug("Diff view is disabled in settings")
            return
        }
        
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }
            
            for (file in modifiedFiles) {
                showDiffForFile(file)
            }
        }
    }
    
    /**
     * Shows diff for a single file if it has been modified.
     */
    private fun showDiffForFile(file: VirtualFile) {
        try {
            val originalContent = fileSnapshots[file.path]
            if (originalContent == null) {
                logger.debug("No snapshot found for ${file.path}, skipping diff")
                return
            }
            
            ApplicationManager.getApplication().runReadAction {
                try {
                    if (!file.isValid) {
                        logger.debug("File is no longer valid: ${file.path}")
                        return@runReadAction
                    }
                    
                    val currentContent = String(file.contentsToByteArray(), file.charset)
                    
                    // Only show diff if content has actually changed
                    if (originalContent != currentContent) {
                        showDiff(file, originalContent, currentContent)
                    } else {
                        logger.debug("No changes detected in ${file.path}")
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to read current content for ${file.path}", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Error showing diff for ${file.path}", e)
        }
    }
    
    /**
     * Displays the diff using IntelliJ's built-in diff viewer.
     */
    private fun showDiff(file: VirtualFile, originalContent: String, modifiedContent: String) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }
            
            try {
                val diffContentFactory = DiffContentFactory.getInstance()
                val originalContentDiff = diffContentFactory.create(project, originalContent, file.fileType)
                val modifiedContentDiff = diffContentFactory.create(project, modifiedContent, file.fileType)
                
                val diffRequest = SimpleDiffRequest(
                    "Codex Changes: ${file.name}",
                    originalContentDiff,
                    modifiedContentDiff,
                    "Original",
                    "Modified by Codex"
                )
                
                DiffManager.getInstance().showDiff(project, diffRequest)
                logger.info("Displayed diff for ${file.path}")
            } catch (e: Exception) {
                logger.error("Failed to show diff for ${file.path}", e)
            }
        }
    }
    
    /**
     * Clears all stored file snapshots.
     */
    fun clearSnapshots() {
        fileSnapshots.clear()
        logger.debug("Cleared all file snapshots")
    }
    
    /**
     * Gets the number of files currently tracked.
     */
    fun getSnapshotCount(): Int = fileSnapshots.size
    
    override fun dispose() {
        clearSnapshots()
    }
}
