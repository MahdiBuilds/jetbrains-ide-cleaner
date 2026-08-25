package com.github.mahdibuilds.jetbrainsidecleaner

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import java.io.File

class ClearIDEAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        // 1. Safety Filter: Get the current active IDE's exact paths
        val currentConfigPath = File(PathManager.getConfigPath()).absolutePath
        val currentSystemPath = File(PathManager.getSystemPath()).absolutePath

        val userHome = System.getProperty("user.home")
        val targetDirectories = mutableListOf<File>()

        when {
            SystemInfo.isMac -> {
                targetDirectories.add(File(userHome, "Library/Application Support/JetBrains"))
                targetDirectories.add(File(userHome, "Library/Caches/JetBrains"))
            }
            SystemInfo.isWindows -> {
                targetDirectories.add(File(System.getenv("APPDATA") ?: "", "JetBrains"))
                targetDirectories.add(File(System.getenv("LOCALAPPDATA") ?: "", "JetBrains"))
            }
            SystemInfo.isLinux -> {
                targetDirectories.add(File(userHome, ".config/JetBrains"))
                targetDirectories.add(File(userHome, ".cache/JetBrains"))
            }
        }

        val foldersToRemove = mutableListOf<File>()
        var totalBytesToFree: Long = 0

        // 2. Scan Directories for old version folders
        for (baseDir in targetDirectories) {
            if (baseDir.exists() && baseDir.isDirectory) {
                val children = baseDir.listFiles() ?: continue
                for (child in children) {
                    if (child.isDirectory) {
                        // Strict Safety Check: Do not touch the currently running IDE
                        if (child.absolutePath == currentConfigPath || child.absolutePath == currentSystemPath) {
                            continue
                        }

                        // Regex matching JetBrains versioned folders (e.g., PyCharm2023.2, WebStorm2024.1)
                        if (child.name.matches(Regex(".*20\\d{2}\\.\\d.*"))) {
                            foldersToRemove.add(child)
                            totalBytesToFree += calculateSize(child)
                        }
                    }
                }
            }
        }

        // 3. Handle Empty State
        if (foldersToRemove.isEmpty()) {
            Messages.showInfoMessage("No leftover IDE directories found. Your system is clean!", "ClearIDE")
            return
        }

        // 4. User Confirmation UI
        val sizeInMB = totalBytesToFree / (1024 * 1024)
        val folderNames = foldersToRemove.joinToString("\n") { "• ${it.name}" }

        val message = "Found ${foldersToRemove.size} leftover directories taking up ~${sizeInMB} MB:\n\n$folderNames\n\nDo you want to permanently delete them?"

        val result = Messages.showYesNoDialog(
            e.project,
            message,
            "ClearIDE: Confirm Deletion",
            "Delete Data",
            "Cancel",
            Messages.getWarningIcon()
        )

        // 5. Trigger Background Deletion if Confirmed
        if (result == Messages.YES) {
            deleteFoldersAsync(e, foldersToRemove)
        }
    }

    // Helper to calculate folder size
    private fun calculateSize(directory: File): Long {
        var length: Long = 0
        val files = directory.listFiles()
        if (files != null) {
            for (file in files) {
                length += if (file.isFile) file.length() else calculateSize(file)
            }
        }
        return length
    }

    // Background worker to delete files without freezing the IDE
    private fun deleteFoldersAsync(e: AnActionEvent, folders: List<File>) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(e.project, "Clearing IDE Data...", false) {
            override fun run(indicator: ProgressIndicator) {
                var deletedCount = 0
                for ((index, folder) in folders.withIndex()) {
                    indicator.text = "Deleting: ${folder.name}"
                    indicator.fraction = index.toDouble() / folders.size

                    if (folder.deleteRecursively()) {
                        deletedCount++
                    }
                }
                indicator.fraction = 1.0

                // Return to main thread to show success popup
                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage("Successfully deleted $deletedCount directories.", "ClearIDE Complete")
                }
            }
        })
    }
}