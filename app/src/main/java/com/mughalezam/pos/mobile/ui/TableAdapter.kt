package com.mughalezam.pos.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mughalezam.pos.mobile.R
import com.mughalezam.pos.mobile.api.TableRow

/**
 * The floor as the main server sees it: green is free, red already has an
 * open bill - tapping a red table adds to what is already running there,
 * exactly like the till does, so two waiters cannot open a second bill on
 * the same table.
 */
class TableAdapter(
    private var rows: List<TableRow>,
    private var busy: Set<String>,
    private val onPick: (TableRow) -> Unit
) : RecyclerView.Adapter<TableAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tableName)
        val seats: TextView = view.findViewById(R.id.tableSeats)
        val status: TextView = view.findViewById(R.id.tableStatus)
        val card: View = view.findViewById(R.id.tableCard)
    }

    fun submit(newRows: List<TableRow>, newBusy: Set<String>) {
        rows = newRows
        busy = newBusy
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_table, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val context = holder.itemView.context

        holder.name.text = row.name
        holder.seats.text = when {
            row.section.isNotEmpty() && row.seats > 0 ->
                "${row.section} · ${row.seats} seats"
            row.section.isNotEmpty() -> row.section
            row.seats > 0 -> "${row.seats} seats"
            else -> ""
        }

        val taken = busy.contains(row.id)
        holder.status.setText(if (taken) R.string.tables_busy else R.string.tables_free)
        holder.status.setBackgroundResource(
            if (taken) R.drawable.pill_red else R.drawable.pill_green
        )
        holder.card.alpha = if (taken) 0.72f else 1.0f
        holder.card.contentDescription = "${row.name} ${context.getString(
            if (taken) R.string.tables_busy else R.string.tables_free
        )}"

        holder.card.setOnClickListener { onPick(row) }
    }
}
