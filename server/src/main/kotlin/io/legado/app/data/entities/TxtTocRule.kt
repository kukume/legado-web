package io.legado.app.data.entities

/**
 * TXT 目录规则，字段与 App 相同（JSON 导入导出兼容）。
 * 自带规则的 id 为负数（-1 ~ -100），自定义规则的 id 为创建时间。
 */
data class TxtTocRule(
    var id: Long = System.currentTimeMillis(),
    var name: String = "",
    var rule: String = "",
    var replacement: String = "",
    var example: String? = null,
    var serialNumber: Int = -1,
    var enable: Boolean = true
) {

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is TxtTocRule) {
            return id == other.id
        }
        return false
    }

}
