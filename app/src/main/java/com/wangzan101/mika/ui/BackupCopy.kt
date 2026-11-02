package com.wangzan101.mika.ui

import androidx.annotation.StringRes
import com.wangzan101.mika.R
import com.wangzan101.mika.backup.BackupStage
import com.wangzan101.mika.core.backup.BackupError
import com.wangzan101.mika.core.backup.BackupFormat

/** 备份页用的文字对照;所有条目都在 strings_backup.xml 里,九种语言齐全。 */
object BackupCopy {
    @StringRes
    fun stage(stage: BackupStage): Int = when (stage) {
        BackupStage.Preparing -> R.string.backup_progress_preparing
        BackupStage.Packing -> R.string.backup_progress_packing
        BackupStage.Encrypting -> R.string.backup_progress_encrypting
        BackupStage.Writing -> R.string.backup_progress_writing
        BackupStage.Reading -> R.string.backup_progress_reading
        BackupStage.Decrypting -> R.string.backup_progress_decrypting
        BackupStage.Extracting -> R.string.backup_progress_extracting
        BackupStage.Restoring -> R.string.backup_progress_restoring
        BackupStage.Done -> R.string.backup_progress_done
    }

    @StringRes
    fun error(code: BackupError): Int = when (code) {
        BackupError.NotMikaBackup -> R.string.backup_error_not_mika
        BackupError.NewerFormat -> R.string.backup_error_newer
        BackupError.Corrupt -> R.string.backup_error_corrupt
        BackupError.UnreadableData -> R.string.backup_error_unreadable
        BackupError.BadPassphrase -> R.string.backup_error_passphrase
        BackupError.TooLarge -> R.string.backup_error_too_large
        BackupError.UnsafePath -> R.string.backup_error_unsafe
        BackupError.Unsupported -> R.string.backup_error_unsupported
        BackupError.NoSpace -> R.string.backup_error_no_space
        BackupError.Busy -> R.string.backup_error_busy
        BackupError.Cancelled -> R.string.backup_error_cancelled
    }

    @StringRes
    fun section(section: String): Int = when (section) {
        BackupFormat.SETTINGS -> R.string.backup_section_settings
        BackupFormat.SECRETS -> R.string.backup_section_secrets
        BackupFormat.CONVERSATIONS -> R.string.backup_section_conversations
        BackupFormat.MEMORY -> R.string.backup_section_memory
        BackupFormat.SKILLS -> R.string.backup_section_skills
        BackupFormat.KNOWLEDGE -> R.string.backup_section_knowledge
        BackupFormat.AUTOMATION -> R.string.backup_section_automation
        BackupFormat.WEB -> R.string.backup_section_web
        BackupFormat.WORKSPACE -> R.string.backup_section_workspace
        else -> R.string.backup_unknown
    }
}
