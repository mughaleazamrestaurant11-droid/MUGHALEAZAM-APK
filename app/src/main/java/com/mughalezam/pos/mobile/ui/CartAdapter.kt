package com.mughalezam.pos.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mughalezam.pos.mobile.R
import com.mughalezam.pos.mobile.api.CartLine
import com.mughalezam.pos.mobile.api.money
import com.mughalezam.pos.mobile.api.qtyText

/**
 * What is on the bill right now.
 *
 * A line the kitchen already has says so and cannot simply vanish: taking it
 * to zero leaves it on the bill as a struck-out line, so the cooks keep the
 * food and the stock count stays honest - the same rule the till follows.
 */
class CartAdapter(
    private var lines: List<CartLine>,
    private val onPlus: (CartLine) -> Unit,
    private val onMinus: (CartLine) -> Unit,
    private val onRemove: (CartLine) -> Unit
) : RecyclerView.Adapter<CartAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.lineName)
        val meta: TextView = view.findViewById(R.id.lineMeta)
        val qty: TextView = view.findViewById(R.id.lineQty)
        val minus: TextView = view.findViewById(R.id.lineMinus)
        val plus: TextView = view.findViewById(R.id.linePlus)
        val remove: TextView = view.findViewById(R.id.lineRemove)
    }

    fun submit(newLines: List<CartLine>) {
        lines = newLines
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cart_line, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = lines.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val line = lines[position]
        val sent = line.sentQty > 0

        holder.name.text = line.name
        holder.qty.text = qtyText(line.qty)

        val bits = ArrayList<String>()
        if (line.code.isNotEmpty()) bits.add(line.code)
        bits.add(money(line.price))
        if (sent) bits.add("kitchen has ${qtyText(line.sentQty)}")
        if (line.note.isNotEmpty()) bits.add("✎ ${line.note}")
        holder.meta.text = bits.joinToString("  ·  ")

        holder.plus.setOnClickListener { onPlus(line) }
        holder.minus.setOnClickListener { onMinus(line) }

        if (sent) {
            // the kitchen already has it - the waiter must ask at the till,
            // because undoing food that is cooking is not this phone's call
            holder.remove.alpha = 0.35f
            holder.remove.setOnClickListener { onRemove(line) }
        } else {
            holder.remove.alpha = 1.0f
            holder.remove.setOnClickListener { onRemove(line) }
        }
    }
}
