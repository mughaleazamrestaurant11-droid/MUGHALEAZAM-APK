package com.mughalezam.pos.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mughalezam.pos.mobile.R
import com.mughalezam.pos.mobile.api.MenuRow
import com.mughalezam.pos.mobile.api.money

/** The dishes in the category the waiter picked. One tap adds one. */
class MenuAdapter(
    private var rows: List<MenuRow>,
    private val onPick: (MenuRow) -> Unit
) : RecyclerView.Adapter<MenuAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.menuName)
        val code: TextView = view.findViewById(R.id.menuCode)
        val price: TextView = view.findViewById(R.id.menuPrice)
        val card: View = view.findViewById(R.id.menuCard)
    }

    fun submit(newRows: List<MenuRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_menu, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.name.text = row.name
        holder.code.text = if (row.code.isEmpty()) row.cat else row.code
        holder.price.text = money(row.price)
        holder.card.contentDescription = "${row.name} ${money(row.price)}"
        holder.card.setOnClickListener { onPick(row) }
    }
}
