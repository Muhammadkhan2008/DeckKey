package com.deckkey.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.deckkey.R
import com.deckkey.core.theme.Theme

class ThemeAdapter(
    private val themes: List<Theme>,
    private var selectedId: String,
    private val onSelect: (Theme) -> Unit,
) : RecyclerView.Adapter<ThemeAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val swatch: View = v.findViewById(R.id.colorSwatch)
        val name: TextView = v.findViewById(R.id.themeName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_theme, parent, false)
    )

    override fun getItemCount() = themes.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = themes[position]
        // Split-color swatch: left = background, right = accent
        val gd = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(t.background, t.modifierActive),
        )
        gd.cornerRadius = holder.swatch.context.resources.displayMetrics.density * 6
        // Highlight selected theme with a white border
        if (t.id == selectedId) {
            gd.setStroke(
                (holder.swatch.context.resources.displayMetrics.density * 2.5f).toInt(),
                Color.WHITE,
            )
        }
        holder.swatch.background = gd
        holder.name.text = t.name
        holder.itemView.setOnClickListener {
            selectedId = t.id
            notifyDataSetChanged()
            onSelect(t)
        }
    }

    fun setSelected(id: String) {
        selectedId = id
        notifyDataSetChanged()
    }
}
