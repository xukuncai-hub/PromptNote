package com.htmlnotes.app

import android.content.Context
import java.io.File

/**
 * 基于应用私有目录的文件存储，每个项目保存为一个 json 文件。
 */
class ProjectStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "projects").apply { if (!exists()) mkdirs() }

    fun list(): List<Project> {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files
            .mapNotNull { f -> runCatching { Project.fromJson(f.readText()) }.getOrNull() }
            // 置顶的排最前，其余按更新时间倒序
            .sortedWith(
                compareByDescending<Project> { it.pinned }
                    .thenByDescending { it.updatedAt }
            )
    }

    fun get(id: String): Project? {
        val f = fileFor(id)
        return if (f.exists()) runCatching { Project.fromJson(f.readText()) }.getOrNull() else null
    }

    fun save(project: Project) {
        project.updatedAt = System.currentTimeMillis()
        fileFor(project.id).writeText(project.toJson())
    }

    fun create(title: String, content: String, type: String = Project.TYPE_HTML): Project {
        val now = System.currentTimeMillis()
        val p = Project(
            id = now.toString(),
            title = title.ifBlank { if (type == Project.TYPE_TEXT) "未命名便签" else "未命名笔记" },
            content = content,
            type = type,
            createdAt = now,
            updatedAt = now,
            colorIndex = (now % ProjectAdapter.noteColors.size).toInt()
        )
        save(p)
        return p
    }

    fun delete(id: String) {
        fileFor(id).delete()
    }

    /** 清空全部便签。 */
    fun clearAll() {
        dir.listFiles { f -> f.extension == "json" }?.forEach { it.delete() }
    }

    /** 切换置顶状态。 */
    fun togglePin(id: String) {
        get(id)?.let { p ->
            p.pinned = !p.pinned
            save(p)
        }
    }

    private fun fileFor(id: String) = File(dir, "$id.json")
}
