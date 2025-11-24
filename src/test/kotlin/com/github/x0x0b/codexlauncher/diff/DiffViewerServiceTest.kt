package com.github.x0x0b.codexlauncher.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.github.x0x0b.codexlauncher.settings.CodexLauncherSettings
import com.intellij.openapi.components.service

class DiffViewerServiceTest : BasePlatformTestCase() {

    fun testCaptureFileSnapshot() {
        val fileText = """
            class TestClass {
                fun testMethod() {
                    println("Hello")
                }
            }
        """.trimIndent()

        val file = myFixture.configureByText("Test.kt", fileText)
        val virtualFile = file.virtualFile
        
        val diffViewerService = project.service<DiffViewerService>()
        diffViewerService.captureFileSnapshot(virtualFile)
        
        assertEquals("Should have captured 1 snapshot", 1, diffViewerService.getSnapshotCount())
    }

    fun testCaptureMultipleFileSnapshots() {
        val file1 = myFixture.addFileToProject("File1.kt", "content1")
        val file2 = myFixture.addFileToProject("File2.kt", "content2")
        
        val diffViewerService = project.service<DiffViewerService>()
        diffViewerService.captureFileSnapshots(listOf(file1.virtualFile, file2.virtualFile))
        
        assertEquals("Should have captured 2 snapshots", 2, diffViewerService.getSnapshotCount())
    }

    fun testClearSnapshots() {
        val file = myFixture.addFileToProject("Test.kt", "content")
        
        val diffViewerService = project.service<DiffViewerService>()
        diffViewerService.captureFileSnapshot(file.virtualFile)
        assertEquals("Should have 1 snapshot", 1, diffViewerService.getSnapshotCount())
        
        diffViewerService.clearSnapshots()
        assertEquals("Should have 0 snapshots after clear", 0, diffViewerService.getSnapshotCount())
    }

    fun testShowDiffsWhenDisabled() {
        // Ensure diff view is disabled
        val settings = service<CodexLauncherSettings>()
        settings.state.showDiffOnChange = false
        
        val file = myFixture.addFileToProject("Test.kt", "original content")
        
        val diffViewerService = project.service<DiffViewerService>()
        diffViewerService.captureFileSnapshot(file.virtualFile)
        
        // Modify file content
        myFixture.openFileInEditor(file.virtualFile)
        myFixture.editor.document.setText("modified content")
        
        // This should not throw an exception even when disabled
        diffViewerService.showDiffsForModifiedFiles(listOf(file.virtualFile))
    }
}
