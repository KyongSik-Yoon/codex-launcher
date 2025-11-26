package com.github.x0x0b.codexlauncher.actions

import com.github.x0x0b.codexlauncher.diff.CodexChangeSnapshotService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger

/**
 * Reverts the current file to the snapshot captured after the last Codex run.
 *
 * This allows a workflow of:
 *  1. Codex applies changes
 *  2. Plugin shows diff / opens file
 *  3. User reviews the changes
 *  4. If they decide to reject, they invoke this action to restore the original content
 *     (or delete the file if it was created by Codex).
 */
class RevertCodexChangesAction : AnAction(
    "Revert Codex Changes",
    "Revert the current file to its state before the last Codex run",
    null
) {

    private val log = Logger.getInstance(RevertCodexChangesAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)

        if (project == null || file == null) {
            return
        }

        val snapshotService = project.service<CodexChangeSnapshotService>()
        val snapshot = snapshotService.getSnapshot(file)

        if (snapshot == null) {
            notify(projectName = project.name, "No Codex snapshot found for this file.", NotificationType.INFORMATION)
            log.info("CodexLauncher: revert requested but no snapshot for ${file.path}")
            return
        }

        snapshotService.revertFileToSnapshot(file)
        notify(projectName = project.name, "Reverted Codex changes for ${file.name}.", NotificationType.INFORMATION)
    }

    private fun notify(projectName: String, content: String, type: NotificationType) {
        try {
            val group = NotificationGroupManager.getInstance().getNotificationGroup("CodexLauncher")
            group.createNotification("Codex Launcher", content, type).notify(null)
        } catch (t: Throwable) {
            log.warn("CodexLauncher: failed to show notification: $content", t)
        }
    }
}

