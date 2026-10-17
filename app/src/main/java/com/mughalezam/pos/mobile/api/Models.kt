package com.mughalezam.pos.mobile.api

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Round 25 - the shapes this phone works with.
 *
 * Every one of these is a straight picture of what the MAIN SERVER sends.
 * The phone never invents a field the server does not know, because the
 * server is the only place an order really exists.
 */

/** One table, as the main server keeps it. */
data class TableRow(
    val id: String,
    val name: String,
    val section: String,
    val seats: Int
)

/** One sellable dish. */
data class MenuRow(
    val id: String,
    val code: String,
    val name: String,
    val cat: String,
    val price: Double
)

/**
 * A line in the phone's cart.
 *
 * [lineId] is kept from the moment the line is born: saving the same line
 * twice updates it instead of adding a second copy.  [sentQty] is what the
 * kitchen already has - this app never lowers it, or the cooks would be left
 * with food the bill says was cancelled.
 */
data class CartLine(
    var lineId: String,
    val itemId: String,
    val code: String,
    val name: String,
    val cat: String,
    val price: Double,
    var qty: Double,
    var sentQty: Double,
    var note: String
)

/* ------------------------------------------------------------------ helpers */

fun readTables(state: JSONObject): List<TableRow> {
    val arr = state.optJSONArray("tables") ?: return emptyList()
    val out = ArrayList<TableRow>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (!o.optBoolean("active", true)) continue
        out.add(
            TableRow(
                id = o.optString("id"),
                name = o.optString("name"),
                section = o.optString("section"),
                seats = o.optInt("seats", 0)
            )
        )
    }
    return out
}

fun readCategories(state: JSONObject): List<String> {
    val menu = state.optJSONObject("menu") ?: return emptyList()
    val arr = menu.optJSONArray("categories") ?: return emptyList()
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        val name = arr.optJSONObject(i)?.optString("name") ?: ""
        if (name.isNotEmpty()) out.add(name)
    }
    return out
}

fun readItems(state: JSONObject, category: String): List<MenuRow> {
    val menu = state.optJSONObject("menu") ?: return emptyList()
    val arr = menu.optJSONArray("items") ?: return emptyList()
    val out = ArrayList<MenuRow>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (!o.optBoolean("active", true)) continue
        if (category.isNotEmpty() && o.optString("cat") != category) continue
        out.add(
            MenuRow(
                id = o.optString("id"),
                code = o.optString("code"),
                name = o.optString("name"),
                cat = o.optString("cat"),
                price = o.optDouble("price", 0.0)
            )
        )
    }
    return out
}

/** The order already open on a table, if the waiter is adding to one. */
fun readOpenOrder(state: JSONObject, tableId: String): JSONObject? {
    val arr = state.optJSONArray("orders") ?: return null
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optString("status") != "open") continue
        // tableId may be a real JSON null on takeaway bills - compare the type
        // too, so a null can never be mistaken for this table
        val tid = o.opt("tableId")
        if (tid !is String || tid != tableId) continue
        return o
    }
    return null
}

fun readOrderItems(order: JSONObject): List<CartLine> {
    val arr = order.optJSONArray("items") ?: return emptyList()
    val out = ArrayList<CartLine>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(
            CartLine(
                lineId = o.optString("lineId"),
                itemId = o.optString("itemId"),
                code = o.optString("code"),
                name = o.optString("name"),
                cat = o.optString("cat"),
                price = o.optDouble("price", 0.0),
                qty = o.optDouble("qty", 0.0),
                sentQty = o.optDouble("sentQty", 0.0),
                note = o.optString("note")
            )
        )
    }
    return out
}

/* ------------------------------------------------------------------ format */

/** Rs 1,200 - and 250.50 when there really are paise. */
fun money(value: Double): String {
    val rounded = Math.round(value * 100.0) / 100.0
    return if (rounded == Math.floor(rounded)) {
        String.format(Locale.US, "%,.0f", rounded)
    } else {
        String.format(Locale.US, "%,.2f", rounded)
    }
}

/** Quantity as the kitchen writes it: 1, 2, 0.5. */
fun qtyText(value: Double): String {
    val rounded = Math.round(value * 100.0) / 100.0
    return if (rounded == Math.floor(rounded)) {
        String.format(Locale.US, "%,.0f", rounded)
    } else {
        String.format(Locale.US, "%,.2f", rounded)
    }
}

/** A client-side line id - the server keeps it and matches on it later. */
fun newLineId(): String =
    "l" + UUID.randomUUID().toString().replace("-", "").take(16)

/** Cart total before the shop's own charges, which the server applies. */
fun cartTotal(lines: List<CartLine>): Double {
    var sum = 0.0
    for (l in lines) sum += l.price * l.qty
    return sum
}

/** How many plates are on the cart right now. */
fun cartCount(lines: List<CartLine>): Double {
    var sum = 0.0
    for (l in lines) sum += l.qty
    return sum
}

/** Anything still waiting to go to the kitchen. */
fun hasUnsent(lines: List<CartLine>): Boolean {
    for (l in lines) if (l.qty > l.sentQty) return true
    return false
}

fun fromArray(arr: JSONArray?): List<JSONObject> {
    val out = ArrayList<JSONObject>()
    if (arr == null) return out
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(o)
    }
    return out
}
