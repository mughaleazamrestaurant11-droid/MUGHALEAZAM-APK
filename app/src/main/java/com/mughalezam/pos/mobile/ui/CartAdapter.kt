package com.mughalezam.pos.mobile.ui

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mughalezam.pos.mobile.R
import com.mughalezam.pos.mobile.api.CartLine
import com.mughalezam.pos.mobile.api.money
import com.mughalezam.pos.mobile.api.qtyText
import kotlin.math.abs

/**
 * What is on the bill right now.
 *
 * The three states below are the till's own three, in the till's own words,
 * because the same line is read by the same people on both screens:
 *
 *   live        - on the bill and in the kitchen
 *   changed     - the next kitchen ticket will send +N or -N
 *   removed     - off the bill, but the kitchen still has it, so the next
 *                 ticket prints a minus and the cooks take it away
 *
 * A removed line is NOT hidden and its bin is NOT dead. Hiding it would let a
 * waiter believe the food was cancelled when the cooks are still making it.
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
        val note: TextView = view.findViewById(R.id.lineNote)
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
        val dead = line.qty <= 0
        val ctx = holder.itemView.context

        holder.name.text = line.name

        // struck out and dimmed once it is off the bill
        if (dead) {
            holder.name.paintFlags = holder.name.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            holder.name.alpha = 0.55f
        } else {
            holder.name.paintFlags = holder.name.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            holder.name.alpha = 1.0f
        }

        val bits = ArrayList<String>()
        if (line.code.isNotEmpty()) bits.add(line.code)
        bits.add(money(line.price))
        if (sent && !dead) bits.add("kitchen has ${qtyText(line.sentQty)}")
        if (line.note.isNotEmpty()) bits.add("✎ ${line.note}")
        holder.meta.text = bits.joinToString("  ·  ")

        // the same sentences the till writes on the line
        val delta = line.qty - line.sentQty
        when {
            dead && sent -> {
                // the minus the cooks will see on their ticket
                holder.qty.text = "−" + qtyText(line.sentQty)
                holder.qty.setTextColor(colorOf(ctx, R.color.red))
                showNote(holder, ctx.getString(R.string.order_removed_note, qtyText(line.sentQty)),
                    R.color.red)
            }
            dead -> {
                holder.qty.text = qtyText(line.qty)
                holder.qty.setTextColor(colorOf(ctx, R.color.text_dim))
                showNote(holder, ctx.getString(R.string.order_removed_now), R.color.text_dim)
            }
            sent && delta != 0.0 -> {
                holder.qty.text = qtyText(line.qty)
                holder.qty.setTextColor(colorOf(ctx, R.color.gold))
                val sign = if (delta > 0) "+" else "−"
                showNote(
                    holder,
                    ctx.getString(R.string.order_change_note, sign + qtyText(abs(delta))),
                    R.color.gold
                )
            }
            else -> {
                holder.qty.text = qtyText(line.qty)
                holder.qty.setTextColor(colorOf(ctx, R.color.gold))
                holder.note.visibility = View.GONE
            }
        }

        holder.plus.setOnClickListener { onPlus(line) }
        holder.minus.setOnClickListener { onMinus(line) }
        holder.remove.setOnClickListener { onRemove(line) }
    }

    private fun showNote(holder: Holder, text: String, colour: Int) {
        holder.note.visibility = View.VISIBLE
        holder.note.text = text
        holder.note.setTextColor(colorOf(holder.itemView.context, colour))
    }

    private fun colorOf(ctx: android.content.Context, id: Int): Int =
        ctx.resources.getColor(id, null)
}
