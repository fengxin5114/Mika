package com.wangzan101.mika.ui

import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.backup.BackupOptions
import com.wangzan101.mika.backup.BackupCancelled
import com.wangzan101.mika.backup.BackupRuntime
import com.wangzan101.mika.backup.BackupStage
import com.wangzan101.mika.backup.ExportResult
import com.wangzan101.mika.core.backup.BackupError
import com.wangzan101.mika.core.backup.BackupException
import com.wangzan101.mika.core.backup.BackupFormat
import com.wangzan101.mika.core.backup.BackupManifest
import com.wangzan101.mika.core.backup.ImportReport
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class BackupMode { Home, Export, Preview, ExportDone, Report }

private data class SectionChoice(
    val title: Int,
    val subtitle: Int?,
    val checked: Boolean,
    val onChecked: (Boolean) -> Unit,
)

/**
 * 设置 › 隐私与数据 › 备份与恢复。
 *
 * 导出:选分类、设口令、SAF 选位置。导入:选文件、预览、输口令、合并、报告。
 * 恢复后进程里的单例仍是旧值,所以只提示用户重启,不在这里杀进程。
 */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { BackupRuntime.get(context) }
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }
    // 口令输入期间不让截图/录屏把这页拍走
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val busy by runtime.busy.collectAsState()
    val progress by runtime.progress.collectAsState()
    val report by runtime.lastReport.collectAsState()
    val snapshot by runtime.lastSnapshot.collectAsState()

    var mode by rememberSaveable { mutableStateOf(BackupMode.Home) }
    var options by remember { mutableStateOf(BackupOptions()) }
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<BackupError?>(null) }
    var manifest by remember { mutableStateOf<BackupManifest?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var exported by remember { mutableStateOf<ExportResult?>(null) }

    val stamp = remember { SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) }

    fun resetForm() {
        passphrase = ""
        confirm = ""
        error = null
        manifest = null
        pendingUri = null
        exported = null
    }

    fun codeOf(t: Throwable): BackupError = when (t) {
        is BackupCancelled -> BackupError.Cancelled
        is BackupException -> t.code
        else -> BackupError.Corrupt
    }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val pass = passphrase.toCharArray()
        scope.launch {
            try {
                exported = runtime.export(pass, options, uri)
                mode = BackupMode.ExportDone
            } catch (t: Exception) {
                error = codeOf(t)
            } finally {
                pass.fill('\u0000')
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingUri = uri
        scope.launch {
            try {
                manifest = runtime.inspect(uri)
                mode = BackupMode.Preview
            } catch (t: Exception) {
                error = codeOf(t)
            }
        }
    }

    PageScaffold(
        title = stringResource(R.string.backup_title),
        onBack = {
            if (mode == BackupMode.Home) onBack() else { mode = BackupMode.Home; resetForm() }
        },
    ) {
        when (mode) {
            BackupMode.Home -> BackupHome(
                onExport = { resetForm(); mode = BackupMode.Export },
                onImport = { resetForm(); open.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
            )
            BackupMode.Export -> BackupExport(
                options = options,
                onOptions = { next -> options = next; if (!next.includeSettings) options = options.copy(includeSecrets = false) },
                passphrase = passphrase,
                confirm = confirm,
                onPassphrase = { passphrase = it },
                onConfirm = { confirm = it },
                busy = busy,
                stage = progress?.stage,
                onCancel = { runtime.cancel() },
                onStart = { create.launch("Mika-backup-$stamp.zip") },
            )
            BackupMode.Preview -> BackupPreview(
                manifest = manifest,
                passphrase = passphrase,
                onPassphrase = { passphrase = it },
                busy = busy,
                stage = progress?.stage,
                onCancel = { runtime.cancel() },
                onStart = {
                    val uri = pendingUri
                    if (uri != null) {
                        val pass = passphrase.toCharArray()
                        scope.launch {
                            try {
                                runtime.restore(pass, uri)
                                mode = BackupMode.Report
                            } catch (t: Exception) {
                                error = codeOf(t)
                            } finally {
                                pass.fill('\u0000')
                            }
                        }
                    }
                },
            )
            BackupMode.ExportDone -> {
                Text(stringResource(R.string.backup_done_export), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                exported?.let { result ->
                    Text(
                        stringResource(
                            R.string.backup_preview_counts,
                            result.manifest.counts[BackupFormat.CONVERSATIONS]?.toInt() ?: 0,
                            result.manifest.counts["memoryItems"]?.toInt() ?: 0,
                            result.manifest.counts[BackupFormat.SKILLS]?.toInt() ?: 0,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                resultWarnings(exported?.warnings.orEmpty())
                Spacer(Modifier.height(16.dp))
                MikaButton(stringResource(R.string.backup_close), { mode = BackupMode.Home; resetForm() }, Modifier.fillMaxWidth())
            }
            BackupMode.Report -> {
                ReportContent(report, snapshot != null, busy, onRollback = {
                    scope.launch {
                        runCatching { runtime.rollbackLatest() }.onFailure { error = codeOf(it) }
                    }
                })
                Spacer(Modifier.height(16.dp))
                MikaButton(stringResource(R.string.backup_close), { mode = BackupMode.Home; resetForm() }, Modifier.fillMaxWidth())
            }
        }
    }

    error?.let { code ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text(stringResource(R.string.backup_error_title)) },
            text = { Text(stringResource(BackupCopy.error(code))) },
            confirmButton = { TextButton({ error = null }) { Text(stringResource(R.string.ok_got_it)) } },
        )
    }
}

@Composable
private fun BackupHome(onExport: () -> Unit, onImport: () -> Unit) {
    Text(stringResource(R.string.backup_home_intro), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(20.dp))
    MikaButton(stringResource(R.string.backup_export), onExport, Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    MikaButton(stringResource(R.string.backup_import), onImport, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.backup_encrypted_note), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun BackupExport(
    options: BackupOptions,
    onOptions: (BackupOptions) -> Unit,
    passphrase: String,
    confirm: String,
    onPassphrase: (String) -> Unit,
    onConfirm: (String) -> Unit,
    busy: Boolean,
    stage: BackupStage?,
    onCancel: () -> Unit,
    onStart: () -> Unit,
) {
    val choices = listOf(
        SectionChoice(R.string.backup_section_settings, R.string.backup_section_settings_sub, options.includeSettings) {
            onOptions(options.copy(includeSettings = it, includeSecrets = it))
        },
        SectionChoice(R.string.backup_section_conversations, null, options.includeConversations) { onOptions(options.copy(includeConversations = it)) },
        SectionChoice(R.string.backup_section_memory, null, options.includeMemory) { onOptions(options.copy(includeMemory = it)) },
        SectionChoice(R.string.backup_section_skills, null, options.includeSkills) { onOptions(options.copy(includeSkills = it)) },
        SectionChoice(R.string.backup_section_knowledge, null, options.includeKnowledge) { onOptions(options.copy(includeKnowledge = it)) },
        SectionChoice(R.string.backup_section_automation, null, options.includeAutomation) { onOptions(options.copy(includeAutomation = it)) },
        SectionChoice(R.string.backup_section_web, R.string.backup_section_web_sub, options.includeWeb) { onOptions(options.copy(includeWeb = it)) },
        SectionChoice(R.string.backup_section_workspace, R.string.backup_section_workspace_sub, options.includeWorkspace) { onOptions(options.copy(includeWorkspace = it)) },
    )

    GroupLabel(stringResource(R.string.backup_sections_label))
    GroupCard {
        choices.forEachIndexed { i, choice ->
            ListRow(
                icon = null,
                title = stringResource(choice.title),
                subtitle = choice.subtitle?.let { stringResource(it) },
                first = i == 0,
            ) {
                MikaSwitch(choice.checked, choice.onChecked, enabled = !busy)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    PasswordField(stringResource(R.string.backup_passphrase), passphrase, onPassphrase, "backup-passphrase")
    PasswordField(stringResource(R.string.backup_passphrase_confirm), confirm, onConfirm, "backup-confirm")
    if (passphrase.isNotEmpty() && passphrase.length < MIN_PASSPHRASE) {
        Hint(stringResource(R.string.backup_passphrase_short))
    } else if (confirm.isNotEmpty() && passphrase != confirm) {
        Hint(stringResource(R.string.backup_passphrase_mismatch))
    }
    Progress(busy, stage, onCancel)
    Spacer(Modifier.height(16.dp))
    MikaButton(
        stringResource(R.string.backup_export_to),
        onStart,
        Modifier.fillMaxWidth(),
        enabled = !busy && passphrase.length >= MIN_PASSPHRASE && passphrase == confirm,
    )
}

@Composable
private fun BackupPreview(
    manifest: BackupManifest?,
    passphrase: String,
    onPassphrase: (String) -> Unit,
    busy: Boolean,
    stage: BackupStage?,
    onCancel: () -> Unit,
    onStart: () -> Unit,
) {
    if (manifest == null) {
        Text(stringResource(R.string.backup_reading), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val context = LocalContext.current
    val sectionLabels = manifest.sections.joinToString(", ") { context.getString(BackupCopy.section(it)) }
    GroupCard {
        ListRow(null, stringResource(R.string.backup_preview_version, manifest.appVersion), first = true)
        ListRow(null, stringResource(R.string.backup_preview_date, manifest.exportedAt))
        ListRow(
            null,
            stringResource(
                R.string.backup_preview_counts,
                manifest.counts[BackupFormat.CONVERSATIONS]?.toInt() ?: 0,
                manifest.counts["memoryItems"]?.toInt() ?: 0,
                manifest.counts[BackupFormat.SKILLS]?.toInt() ?: 0,
            ),
        )
        ListRow(null, stringResource(R.string.backup_preview_sections, sectionLabels))
    }
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(if (BackupFormat.SECRETS in manifest.sections) R.string.backup_preview_has_secrets else R.string.backup_preview_no_secrets),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(R.string.backup_preview_counts_unverified),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
    Spacer(Modifier.height(16.dp))
    PasswordField(stringResource(R.string.backup_passphrase), passphrase, onPassphrase, "backup-restore-passphrase")
    Progress(busy, stage, onCancel)
    Spacer(Modifier.height(16.dp))
    MikaButton(
        stringResource(R.string.backup_start_restore),
        onStart,
        Modifier.fillMaxWidth(),
        enabled = !busy && passphrase.length >= MIN_PASSPHRASE,
    )
}

@Composable
private fun ReportContent(report: ImportReport?, hasSnapshot: Boolean, busy: Boolean, onRollback: () -> Unit) {
    Text(stringResource(R.string.backup_report_title), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    if (report == null) {
        Text(stringResource(R.string.backup_reading), style = MaterialTheme.typography.bodyMedium)
        return
    }
    if (report.rolledBack) {
        Text(stringResource(R.string.backup_report_rolled_back), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
    }
    report.sections.forEach { row ->
        Text(
            stringResource(
                R.string.backup_report_section,
                stringResource(BackupCopy.section(row.section)),
                row.added, row.replaced, row.kept, row.skipped,
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
    if (report.unavailableSecrets.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.backup_report_secrets_unavailable, report.unavailableSecrets.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (report.warnings.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.backup_report_warnings_count, report.warnings.size),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.backup_restart_hint), style = MaterialTheme.typography.bodyMedium)
    if (hasSnapshot) {
        Spacer(Modifier.height(16.dp))
        MikaButton(stringResource(R.string.backup_rollback), onRollback, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, enabled = !busy)
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit, testTag: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Progress(busy: Boolean, stage: BackupStage?, onCancel: () -> Unit) {
    if (!busy) return
    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
    stage?.let {
        Text(stringResource(BackupCopy.stage(it)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
    Spacer(Modifier.height(8.dp))
    MikaButton(
        stringResource(R.string.cancel), onCancel, Modifier.fillMaxWidth(),
        kind = ButtonKind.Secondary, size = ButtonSize.Small,
    )
}

@Composable
private fun resultWarnings(warnings: List<String>) {
    if (warnings.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.backup_export_warnings, warnings.joinToString(", ")),
        style = MaterialTheme.typography.bodySmall,
    )
}

private const val MIN_PASSPHRASE = 8
