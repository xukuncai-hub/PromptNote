package com.htmlnotes.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.htmlnotes.app.databinding.ItemProjectBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 便签网格适配器：低饱和柔和色卡片，点击打开预览，长按弹出置顶/删除菜单。
 */
class ProjectAdapter(
    private var items: List<Project>,
    private val onAction: (Project, Action, View) -> Unit
) : RecyclerView.Adapter<ProjectAdapter.VH>() {

    enum class Action { OPEN, EDIT, DELETE, PIN }

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val yearFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private fun formatSmartTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = (now - timestamp).coerceAtLeast(0L)
        val oneMinute = 60_000L
        val oneHour = 60 * oneMinute
        val oneDay = 24 * oneHour

        return when {
            diff < oneMinute -> "刚刚"
            diff < oneHour -> "${diff / oneMinute}分钟前"
            diff < oneDay -> {
                val calNow = java.util.Calendar.getInstance().apply { timeInMillis = now }
                val calThen = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
                if (calNow.get(java.util.Calendar.DAY_OF_YEAR) == calThen.get(java.util.Calendar.DAY_OF_YEAR)) {
                    "今天 " + timeFormat.format(Date(timestamp))
                } else {
                    "昨天 " + timeFormat.format(Date(timestamp))
                }
            }
            diff < 2 * oneDay -> {
                "昨天 " + timeFormat.format(Date(timestamp))
            }
            else -> {
                val calNow = java.util.Calendar.getInstance().apply { timeInMillis = now }
                val calThen = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
                if (calNow.get(java.util.Calendar.YEAR) == calThen.get(java.util.Calendar.YEAR)) {
                    dateFormat.format(Date(timestamp))
                } else {
                    yearFormat.format(Date(timestamp))
                }
            }
        }
    }

    inner class VH(val binding: ItemProjectBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        val b = holder.binding
        val context = b.root.context
        // 卡片底色：同一批颜色 id，浅色/深色值分别在 values/colors.xml 与
        // values-night/colors.xml 中定义，按当前系统配置自动解析，不用手写 isNight 判断
        b.root.setCardBackgroundColor(
            ContextCompat.getColor(context, colorIds[p.colorIndex % colorIds.size])
        )
        b.pinBadge.visibility = if (p.pinned) View.VISIBLE else View.GONE
        val snippet = snippetOf(p.content)
        // 标题：用户自定义过（非默认占位）时用用户标题；否则从 HTML 提取 <title>/<h1>/<h2>，
        // 提取不到再显示「无标题笔记」——`<!DOCTYPE` 这类源码永远不会出现在界面上
        b.title.text = displayTitle(p, context)
        b.snippet.text = snippet
        b.date.text = formatSmartTime(p.updatedAt)

        // 笔记类型徽章
        b.typeTag.text = context.getString(
            if (p.type == Project.TYPE_TEXT) R.string.text_note else R.string.html_note
        )

        // 把标题 / 类型 / 置顶 / 摘要合并成一条描述，避免 TalkBack 逐个念读零散子视图
        b.root.contentDescription = buildString {
            append(b.title.text)
            append('，').append(b.typeTag.text)
            if (p.pinned) append('，').append(context.getString(R.string.a11y_pinned))
            if (snippet.isNotBlank() && snippet != BLANK_SNIPPET) append('，').append(snippet)
        }
        // 每张卡片的编辑按钮都叫「编辑便签」的话无法区分，带上标题
        b.btnEdit.contentDescription = context.getString(R.string.edit_note_named, b.title.text)

        // 微动画：卡片与编辑按钮触摸反馈
        b.root.attachPressScale(0.97f, 90L)
        b.btnEdit.attachPressScale(0.85f, 90L)

        // 过渡动画源视图统一用整张卡片：编辑按钮太小，从它展开视觉上不连贯
        b.root.setOnClickListener { onAction(p, Action.OPEN, b.root) }
        b.btnEdit.setOnClickListener { onAction(p, Action.EDIT, b.root) }
        b.root.setOnLongClickListener {
            onAction(p, Action.PIN, b.root)
            true
        }
    }

    /**
     * 卡片标题策略：
     * 1. 用户手动命名（标题不等于「未命名笔记/未命名便签」默认占位）→ 用用户标题；
     * 2. 默认占位 → HtmlText.extractTitle() 提取 <title>/<h1>/<h2> 或纯文本前缀；
     * 3. 内容也没有可读文本 → 「无标题笔记」。
     */
    private fun displayTitle(p: Project, context: android.content.Context): String {
        val defaultTitle = context.getString(
            if (p.type == Project.TYPE_TEXT) R.string.unnamed_text else R.string.unnamed_html
        )
        if (p.title != defaultTitle) return p.title
        return extractTitle(p.content) ?: context.getString(R.string.untitled_note)
    }

    /**
     * 用 DiffUtil 增量更新：搜索时每敲一个字都会重新过滤列表，
     * 全量 notifyDataSetChanged 会让所有卡片重新绑定并重放错落入场动画，出现明显闪烁。
     */
    fun submit(newItems: List<Project>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                items[oldPos].id == newItems[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                items[oldPos] == newItems[newPos]
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    companion object {
        /**
         * 卡片底色 id（低饱和柔和色，6 色轮换）。浅色值在 values/colors.xml，
         * 深色值在 values-night/colors.xml，ContextCompat.getColor 按当前配置自动取色。
         */
        val colorIds = intArrayOf(
            R.color.card_bg_0,
            R.color.card_bg_1,
            R.color.card_bg_2,
            R.color.card_bg_3,
            R.color.card_bg_4,
            R.color.card_bg_5
        )
    }
}
