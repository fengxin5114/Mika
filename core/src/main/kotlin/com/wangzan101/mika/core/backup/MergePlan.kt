package com.wangzan101.mika.core.backup

enum class MergeAction { Add, Replace, Keep, Skip }

data class MergeDecision<T>(val action: MergeAction, val imported: T, val existing: T? = null, val reason: String? = null)

/**
 * 恢复时的合并决策。只决定「这一条怎么办」,真正写文件由调用方做,方便在 JVM 上把所有分类的规则测一遍。
 */
object MergePlan {
    /**
     * 按 id 合并。导入里有、目标没有 → Add;两边都有 → [replaceWhen] 为真才 Replace,否则 Keep。
     * 目标里有、导入里没有的条目不产生决策(保持原样)。
     */
    fun <T> byId(
        imported: List<T>,
        existing: List<T>,
        id: (T) -> String,
        replaceWhen: (imported: T, existing: T) -> Boolean = { _, _ -> false },
    ): List<MergeDecision<T>> {
        val current = existing.associateBy(id)
        return imported.map { item ->
            val old = current[id(item)]
            when {
                old == null -> MergeDecision(MergeAction.Add, item)
                replaceWhen(item, old) -> MergeDecision(MergeAction.Replace, item, old)
                else -> MergeDecision(MergeAction.Keep, item, old)
            }
        }
    }

    /** 目标里缺的才补;已有的保持。用于草稿、归档、路线这类不做覆盖的东西。 */
    fun <T> fillMissing(imported: List<T>, existing: List<T>, id: (T) -> String): List<MergeDecision<T>> =
        byId(imported, existing, id) { _, _ -> false }

    /** 导入里有、目标里没有的键。用于偏好设置:只补缺失,不盖用户当前的选择。 */
    fun <V> missingKeys(imported: Map<String, V>, existing: Map<String, V>): Map<String, V> =
        imported.filterKeys { it !in existing }

    /** 保护性更强的并集:App 访问清单里的「禁止」两边都要保留。 */
    fun <T> union(imported: Set<T>, existing: Set<T>): Set<T> = imported + existing

    /** 保护性更强的交集:分享白名单两边都允许才保留。 */
    fun <T> intersect(imported: Set<T>, existing: Set<T>): Set<T> = imported intersect existing

    /** 两边都是 true 才为 true:自动审批只在两边都开着时保留。 */
    fun stricter(imported: Boolean, existing: Boolean): Boolean = imported && existing
}
