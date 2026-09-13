package com.htmlnotes.app

import org.json.JSONObject

/**
 * 一个便签项目：标题 + 内容。
 * type = "text" 表示普通文本便签，type = "html" 表示 HTML 页面便签。
 */
data class Project(
    val id: String,
    var title: String,
    var content: String,
    var type: String,
    val createdAt: Long,
    var updatedAt: Long,
    var colorIndex: Int,
    var pinned: Boolean = false
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("id", id)
        o.put("title", title)
        o.put("html", content) // 保留原字段名，兼容旧数据
        o.put("type", type)
        o.put("createdAt", createdAt)
        o.put("updatedAt", updatedAt)
        o.put("colorIndex", colorIndex)
        o.put("pinned", pinned)
        return o.toString()
    }

    companion object {
        fun fromJson(s: String): Project {
            val o = JSONObject(s)
            return Project(
                id = o.getString("id"),
                title = o.getString("title"),
                content = o.getString("html"),
                type = o.optString("type", TYPE_HTML),
                createdAt = o.getLong("createdAt"),
                updatedAt = o.getLong("updatedAt"),
                colorIndex = o.optInt("colorIndex", 0),
                pinned = o.optBoolean("pinned", false)
            )
        }

        const val TYPE_TEXT = "text"
        const val TYPE_HTML = "html"
    }
}
