package com.mughalezam.pos.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mughalezam.pos.mobile.R

/**
 * The main servers this phone found on the wi-fi. Tap one to fill it in.
 */
class ServerAdapter(
    private val rows: List<String>,
    private val onPick: (String) -> Unit
) : RecyclerView.Adapter<ServerAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.serverRow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_server, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val url = rows[position]
        holder.text.text = url
        holder.text.setOnClickListener { onPick(url) }
    }
}
