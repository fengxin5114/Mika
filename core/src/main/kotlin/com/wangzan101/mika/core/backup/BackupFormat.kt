package com.wangzan101.mika.core.backup

/** 备份文件的固定约定。改动格式时只往后加版本,不改变已有名字的含义。 */
object BackupFormat {
    const val APP = "mika"

    /** 当前写出的格式版本;读入时接受 1..VERSION。 */
    const val VERSION = 1

    const val MANIFEST_ENTRY = "manifest.json"
    const val PAYLOAD_ENTRY = "payload.bin"
    const val PAYLOAD_PLAINTEXT = "zip"
    const val KEY_MODE_PASSPHRASE = "passphrase"

    /** AES-GCM 的附加认证数据前缀;实际 AAD 还带上格式、版本、导出时间和分类。 */
    const val AAD_PREFIX = "mika-backup-v1"

    const val PBKDF2 = "PBKDF2WithHmacSHA256"
    const val AES_GCM = "AES/GCM/NoPadding"
    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val TAG_BITS = 128
    const val KEY_BITS = 256

    /** 默认迭代次数;写进 manifest,以后可以调高。 */
    const val DEFAULT_ITERATIONS = 210_000
    const val MIN_ITERATIONS = 100_000
    const val MAX_ITERATIONS = 2_000_000

    /** manifest 本身的上限;它应该只有几 KB。 */
    const val MAX_MANIFEST_BYTES = 1L shl 20

    const val MAX_ENTRIES = 100_000
    const val MAX_ENTRY_BYTES = 4L shl 30
    const val MAX_TOTAL_BYTES = 8L shl 30
    const val MAX_COMPRESSION_RATIO = 1000L
    const val RATIO_CHECK_BYTES = 10L shl 20

    const val SETTINGS = "settings"
    const val SECRETS = "secrets"
    const val CONVERSATIONS = "conversations"
    const val MEMORY = "memory"
    const val SKILLS = "skills"
    const val KNOWLEDGE = "knowledge"
    const val AUTOMATION = "automation"
    const val WEB = "web"
    const val WORKSPACE = "workspace"

    /** 写进备份的分类;工作区要显式勾选。 */
    val ALL_SECTIONS = listOf(SETTINGS, SECRETS, CONVERSATIONS, MEMORY, SKILLS, KNOWLEDGE, AUTOMATION, WEB, WORKSPACE)

    val DEFAULT_SECTIONS = listOf(SETTINGS, SECRETS, CONVERSATIONS, MEMORY, SKILLS, KNOWLEDGE, AUTOMATION, WEB)

    /**
     * manifest 里的计数键。口径统一成「条目数」,工作区体积单独放在 [COUNT_WORKSPACE_BYTES]。
     * 导入时按这些键逐项对账;`countsMac` 用同一份规范化字符串认证。
     */
    const val COUNT_SETTINGS_FILES = "settingsFiles"
    const val COUNT_PREFS_FEATURE_KEYS = "prefsFeatureKeys"
    const val COUNT_PREFS_LANGUAGE = "prefsLanguage"
    const val COUNT_PROVIDER_KEYS = "providerKeys"
    const val COUNT_SECRET_ITEMS = "secretItems"
    const val COUNT_CONVERSATIONS = "conversations"
    const val COUNT_MEMORY_ITEMS = "memoryItems"
    const val COUNT_PROFILE_ENTRIES = "profileEntries"
    const val COUNT_PERSONAL_SKILLS = "personalSkills"
    const val COUNT_PENDING_SKILLS = "pendingSkills"
    const val COUNT_SKILL_ARCHIVES = "skillArchives"
    const val COUNT_KNOWLEDGE_DRAFTS = "knowledgeDrafts"
    const val COUNT_KNOWLEDGE_NOTES = "knowledgeNotes"
    const val COUNT_TRIGGERS = "triggers"
    const val COUNT_TRIGGER_WINDOWS = "triggerWindows"
    const val COUNT_ROUTES = "routes"
    const val COUNT_APPROVALS = "approvals"
    const val COUNT_APP_ACCESS = "appAccess"
    const val COUNT_WEB_SITES = "webSites"
    const val COUNT_WORKSPACE_FILES = "workspaceFiles"
    const val COUNT_WORKSPACE_BYTES = "workspaceBytes"

    val COUNTS = listOf(
        COUNT_SETTINGS_FILES, COUNT_PREFS_FEATURE_KEYS, COUNT_PREFS_LANGUAGE, COUNT_PROVIDER_KEYS, COUNT_SECRET_ITEMS,
        COUNT_CONVERSATIONS, COUNT_MEMORY_ITEMS, COUNT_PROFILE_ENTRIES, COUNT_PERSONAL_SKILLS, COUNT_PENDING_SKILLS,
        COUNT_SKILL_ARCHIVES, COUNT_KNOWLEDGE_DRAFTS, COUNT_KNOWLEDGE_NOTES, COUNT_TRIGGERS, COUNT_TRIGGER_WINDOWS,
        COUNT_ROUTES, COUNT_APPROVALS, COUNT_APP_ACCESS, COUNT_WEB_SITES, COUNT_WORKSPACE_FILES, COUNT_WORKSPACE_BYTES,
    )
}

/** 备份失败的类型。界面按它取一句人话,不要拿异常的文字直接给用户看。 */
enum class BackupError {
    /** 不是 Mika 的备份。 */
    NotMikaBackup,

    /** 包里的格式版本比这个 App 新。 */
    NewerFormat,

    /** 文件损坏、截断、CRC 不对、结构不合法。 */
    Corrupt,

    /** 用户的数据本身读不出(例如记忆密钥丢了);不能带着 0 条继续导出。 */
    UnreadableData,

    /** 口令不对,或密文的认证标签对不上。 */
    BadPassphrase,

    /** 条目太多、太大,或压缩比异常。 */
    TooLarge,

    /** zip 里有绝对路径、`..`、反斜杠等不安全的条目。 */
    UnsafePath,

    /** 当前 App 版本不支持这种备份(例如未知的 keyMode / 算法)。 */
    Unsupported,

    /** 设备空间不够。 */
    NoSpace,

    /** 有任务在跑,现在不能恢复。 */
    Busy,

    /** 读写被取消。 */
    Cancelled,
}

class BackupException(val code: BackupError, message: String, cause: Throwable? = null) : Exception(message, cause)
