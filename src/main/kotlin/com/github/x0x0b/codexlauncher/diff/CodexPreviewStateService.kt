package com.github.x0x0b.codexlauncher.diff

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

/**
 * Project-level service for tracking which files have already shown a Codex
 * preview diff in the current session.
 *
 * This allows us to suppress a second VCS diff after /refresh for the same file,
 * so the user only sees a single preview diff per Codex run.
 */
@Service(Service.Level.PROJECT)
class CodexPreviewStateService(private val project: Project) : Disposable {

    private val logger = logger<CodexPreviewStateService>()
    private val previewedPaths = mutableSetOf<String>()

    fun markPreviewShown(path: String) {
        synchronized(previewedPaths) {
            previewedPaths.add(path)
        }
        logger.debug("CodexLauncher: marked preview shown for $path")
    }

    /**
     * Returns true if a preview was previously shown for [path], and clears the mark
     * so subsequent runs can show VCS diffs again.
     */
    fun consumePreviewShown(path: String): Boolean {
        synchronized(previewedPaths) {
            val removed = previewedPaths.remove(path)
            if (removed) {
                logger.debug("CodexLauncher: consuming preview mark for $path")
            }
            return removed
        }
    }

    override fun dispose() {
        synchronized(previewedPaths) {
            previewedPaths.clear()
        }
    }
}

