package com.wangzan101.mika.core.backup

/** 一个分类恢复了多少条;`note` 是给界面的一句补充(例如「登录状态需重新确认」)。 */
data class SectionReport(
    val section: String,
    val added: Int = 0,
    val replaced: Int = 0,
    val kept: Int = 0,
    val skipped: Int = 0,
    val note: String? = null,
) {
    operator fun plus(other: SectionReport): SectionReport = SectionReport(
        section,
        added + other.added,
        replaced + other.replaced,
        kept + other.kept,
        skipped + other.skipped,
        listOfNotNull(note, other.note).distinct().joinToString("; ").takeIf { it.isNotEmpty() },
    )
}

data class ImportReport(
    val sections: List<SectionReport> = emptyList(),
    /** 导出时读不出的密钥(别名或服务商 id),导入时会在报告里提示。 */
    val unavailableSecrets: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** 写入失败后是否已经用快照回滚。 */
    val rolledBack: Boolean = false,
) {
    fun section(name: String): SectionReport? = sections.firstOrNull { it.section == name }
}

/** 边恢复边记;同一个分类多次上报会累加。 */
class ImportReportBuilder {
    private val sections = LinkedHashMap<String, SectionReport>()
    private val unavailable = mutableListOf<String>()
    private val warnings = mutableListOf<String>()

    fun add(section: String, added: Int = 0, replaced: Int = 0, kept: Int = 0, skipped: Int = 0, note: String? = null) {
        val row = SectionReport(section, added, replaced, kept, skipped, note)
        sections[section] = sections[section]?.plus(row) ?: row
    }

    fun unavailable(name: String) {
        if (name !in unavailable) unavailable += name
    }

    fun warn(message: String) {
        if (message !in warnings) warnings += message
    }

    fun build(rolledBack: Boolean = false): ImportReport =
        ImportReport(sections.values.toList(), unavailable.toList(), warnings.toList(), rolledBack)
}
