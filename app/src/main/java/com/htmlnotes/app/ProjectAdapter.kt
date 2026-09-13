package com.htmlnotes.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.htmlnotes.app.databinding.ItemProjectBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 便签网格适配器：OPPO 式彩色卡片，点击打开预览，长按弹出置顶/删除菜单。
 */
class ProjectAdapter(
    private var items: List<Project>,
    private val onAction: (Project, Action) -> Unit
) : RecyclerView.Adapter<ProjectAdapter.VH>() {

    enum class Action { OPEN, EDIT, DELETE, PIN }

    inner class VH(val binding: ItemProjectBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        val b = holder.binding
        b.root.setCardBackgroundColor(noteColors[p.colorIndex % noteColors.size])
        b.pinIcon.visibility = if (p.pinned) View.VISIBLE else View.GONE
        b.typeIcon.setImageResource(
            if (p.type == Project.TYPE_TEXT) R.drawable.ic_text else R.drawable.ic_code
        )
        b.title.text = p.title
        b.snippet.text = snippetOf(p.content)
        b.date.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(p.updatedAt))
        b.root.setOnClickListener { onAction(p, Action.OPEN) }
        b.btnEdit.setOnClickListener { onAction(p, Action.EDIT) }
        b.root.setOnLongClickListener {
            onAction(p, Action.PIN)
            true
        }
    }

    fun submit(newItems: List<Project>) {
        items = newItems
        notifyDataSetChanged()
    }

    companion object {
        /** OPPO 式柔和卡片底色，浅色系在明暗模式下均保持清晰。 */
        val noteColors = intArrayOf(
            0xFFF9F0DC.toInt(), // 米黄
            0xFFE4F2E8.toInt(), // 浅绿
            0xFFE2EEF9.toInt(), // 浅蓝
            0xFFF9E3DE.toInt(), // 浅珊瑚
            0xFFEFE7F6.toInt(), // 浅紫
            0xFFE2F1F0.toInt()  // 浅青
        )
    }
}

/** 从 HTML 或文本中提取摘要。 */
private fun snippetOf(html: String): String {
    val text = html
        .replace(Regex("<[^>]*>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return text.ifBlank { "（空白）" }.take(80)
}
