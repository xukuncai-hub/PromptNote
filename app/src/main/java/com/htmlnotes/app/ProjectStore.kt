package com.htmlnotes.app

import android.content.Context
import java.io.File
import kotlin.random.Random

/**
 * 基于应用私有目录的文件存储，每个项目保存为一个 json 文件。
 * 写入采用「临时文件 + 重命名」原子替换，避免写一半崩溃导致便签损坏。
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
        val target = fileFor(project.id)
        val tmp = File(dir, ".${project.id}.tmp")
        tmp.writeText(project.toJson())
        // 同目录 rename 在 Android 文件系统上是原子操作；失败时回退为直接覆盖
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    fun create(title: String, content: String, type: String = Project.TYPE_HTML): Project {
        val now = System.currentTimeMillis()
        val p = Project(
            id = "${now}-${Random.nextInt(0x100000).toString(16)}",
            title = title.ifBlank { if (type == Project.TYPE_TEXT) "未命名便签" else "未命名笔记" },
            content = content,
            type = type,
            createdAt = now,
            updatedAt = now,
            colorIndex = (now % ProjectAdapter.colorIds.size).toInt()
        )
        save(p)
        return p
    }

    fun delete(id: String) {
        fileFor(id).delete()
        File(dir, ".$id.tmp").delete()
    }

    /** 清空全部便签。 */
    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** 切换置顶状态。 */
    fun togglePin(id: String) {
        get(id)?.let { p ->
            p.pinned = !p.pinned
            save(p)
        }
    }

    /** 计算全部便签占用的磁盘大小（字节）。 */
    fun storageSize(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun fileFor(id: String) = File(dir, "$id.json")
}
