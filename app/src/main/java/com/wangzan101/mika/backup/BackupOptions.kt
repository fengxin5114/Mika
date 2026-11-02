package com.wangzan101.mika.backup

import com.wangzan101.mika.core.backup.BackupFormat
import com.wangzan101.mika.core.backup.BackupManifest
import com.wangzan101.mika.core.backup.SectionReport

/** 导出时勾选的分类;工作区默认不选。 */
data class BackupOptions(
    val includeSettings: Boolean = true,
    val includeSecrets: Boolean = true,
    val includeConversations: Boolean = true,
    val includeMemory: Boolean = true,
    val includeSkills: Boolean = true,
    val includeKnowledge: Boolean = true,
    val includeAutomation: Boolean = true,
    val includeWeb: Boolean = true,
    val includeWorkspace: Boolean = false,
) {
    fun sections(): List<String> = buildList {
        if (includeSettings) add(BackupFormat.SETTINGS)
        if (includeSecrets) add(BackupFormat.SECRETS)
        if (includeConversations) add(BackupFormat.CONVERSATIONS)
        if (includeMemory) add(BackupFormat.MEMORY)
        if (includeSkills) add(BackupFormat.SKILLS)
        if (includeKnowledge) add(BackupFormat.KNOWLEDGE)
        if (includeAutomation) add(BackupFormat.AUTOMATION)
        if (includeWeb) add(BackupFormat.WEB)
        if (includeWorkspace) add(BackupFormat.WORKSPACE)
    }
}

enum class BackupStage { Preparing, Packing, Encrypting, Writing, Reading, Decrypting, Extracting, Restoring, Done }

data class BackupProgress(val stage: BackupStage, val done: Long = 0, val total: Long = 0)

data class ExportResult(
    val manifest: BackupManifest,
    val sections: List<SectionReport>,
    val warnings: List<String> = emptyList(),
)

/** 导入完成后的结果;`snapshot` 是导入前快照的目录,能撤销时非空。 */
data class RestoreResult(val report: com.wangzan101.mika.core.backup.ImportReport, val snapshot: java.io.File?)

/** 用户按了取消,或页面离开;不要当成错误弹窗。 */
class BackupCancelled : Exception("backup cancelled")
