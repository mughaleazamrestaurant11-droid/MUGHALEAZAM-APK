package com.mughalezam.pos.mobile.ui

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.mughalezam.pos.mobile.PosApp
import com.mughalezam.pos.mobile.R
import com.mughalezam.pos.mobile.api.CartLine
import com.mughalezam.pos.mobile.api.MenuRow
import com.mughalezam.pos.mobile.api.PosApi
import com.mughalezam.pos.mobile.api.cartCount
import com.mughalezam.pos.mobile.api.cartTotal
import com.mughalezam.pos.mobile.api.hasUnsent
import com.mughalezam.pos.mobile.api.money
import com.mughalezam.pos.mobile.api.newLineId
import com.mughalezam.pos.mobile.api.qtyText
import com.mughalezam.pos.mobile.api.readCategories
import com.mughalezam.pos.mobile.api.readItems
import com.mughalezam.pos.mobile.api.readOpenOrder
import com.mughalezam.pos.mobile.api.readOrderItems
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Screen 4 - the order.
 *
 * THE RULE OF THIS SCREEN: the bill exists on the main server or it does not
 * exist at all.  The phone keeps a copy of the server's own order and sends
 * it back with the items changed; everything the shop decides - service
 * charge, tax, discount - is copied straight through, never re-invented here.
 * If the main PC is off, saving says so in words instead of letting the
 * waiter believe an order was taken.
 */
class OrderActivity : AppCompatActivity(), PosApi.Listener {

    private lateinit var tableId: String
    private lateinit var tableName: String

    /** The main server's own order, as last seen. null = not created yet. */
    private var serverOrder: JSONObject? = null
    private var orderId: String = ""
    private var invoice: String = ""

    private val cart = ArrayList<CartLine>()
    private lateinit var cartAdapter: CartAdapter
    private lateinit var menuAdapter: MenuAdapter

    private lateinit var cartList: RecyclerView
    private lateinit var menuList: RecyclerView
    private lateinit var cartHead: TextView
    private lateinit var emptyText: TextView
    private lateinit var total: TextView
    private lateinit var count: TextView
    private lateinit var banner: TextView
    private lateinit var dot: TextView
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var cats: ChipGroup
    private lateinit var save: MaterialButton
    private lateinit var kitchen: MaterialButton

    private var items: List<MenuRow> = emptyList()
    private var categories: List<String> = emptyList()
    private var currentCat: String = ""

    /** What the waiter has typed in the search box. */
    private var search = ""
    private lateinit var searchBox: EditText
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    /** The most recent picture the server sent us. */
    private var lastState: JSONObject? = null

    private var active = false
    private var everConnected = false
    private var connected = false
    private var connectFailed = false
    private var startedAt = 0L
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order)

        tableId = intent.getStringExtra(EXTRA_TABLE_ID) ?: ""
        tableName = intent.getStringExtra(EXTRA_TABLE_NAME) ?: ""

        cartList = findViewById(R.id.orderCart)
        menuList = findViewById(R.id.orderMenu)
        cartHead = findViewById(R.id.orderCartHead)
        emptyText = findViewById(R.id.orderEmpty)
        total = findViewById(R.id.orderTotal)
        count = findViewById(R.id.orderCount)
        banner = findViewById(R.id.orderOffline)
        dot = findViewById(R.id.orderDot)
        title = findViewById(R.id.orderTitle)
        sub = findViewById(R.id.orderSub)
        cats = findViewById(R.id.orderCats)
        save = findViewById(R.id.orderSave)
        kitchen = findViewById(R.id.orderKitchen)
        searchBox = findViewById(R.id.orderSearch)

        title.text = tableName
        sub.text = getString(R.string.order_empty)
        startedAt = System.currentTimeMillis()

        bindSearch()

        cartAdapter = CartAdapter(
            cart,
            onPlus = { line -> changeQty(line, +1.0) },
            onMinus = { line -> changeQty(line, -1.0) },
            onRemove = { line -> removeLine(line) },
            onQty = { line -> askQty(line) }
        )
        cartList.layoutManager = LinearLayoutManager(this)
        cartList.adapter = cartAdapter

        menuAdapter = MenuAdapter(emptyList()) { row -> addLine(row) }
        menuList.layoutManager = GridLayoutManager(this, 2)
        menuList.adapter = menuAdapter

        findViewById<MaterialButton>(R.id.orderBack).setOnClickListener { finish() }
        save.setOnClickListener { doSave() }
        kitchen.setOnClickListener { doKitchen() }
        banner.setOnClickListener {
            PosApp.instance.api.connectSocket()
            load()
        }
    }

    override fun onResume() {
        super.onResume()
        active = true
        PosApp.instance.api.listener = this
        load()
    }

    override fun onPause() {
        active = false
        val api = PosApp.instance.api
        if (api.listener === this) api.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        searchRunnable = null
        super.onDestroy()
    }

    /* ------------------------------------------------------------ search */

    /**
     * The till's search box, waiting 150ms before it works.
     *
     * The pause is not decoration: filtering the whole menu on every single
     * keystroke makes the list jump under the waiter's thumb while they are
     * still typing, which is exactly when they cannot look at it.
     */
    private fun bindSearch() {
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                val run = Runnable {
                    search = s?.toString()?.trim() ?: ""
                    showMenu()
                }
                searchRunnable = run
                searchHandler.postDelayed(run, SEARCH_DEBOUNCE_MS)
            }
        })
    }

    /* ------------------------------------------------------------- loading */

    private fun load() {
        lifecycleScope.launch {
            try {
                val state = PosApp.instance.api.stateOn()
                if (!active) return@launch
                lastState = state
                categories = readCategories(state)
                buildChips()
                showMenu()

                // a table can be taken while we were walking towards it
                val running = readOpenOrder(state, tableId)
                if (running != null) {
                    adopt(running)
                }
                paintTotals()
            } catch (e: Exception) {
                if (!active) return@launch
                banner.visibility = View.VISIBLE
                Toast.makeText(this@OrderActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Take over an order that is already running on this table. */
    private fun adopt(order: JSONObject) {
        val id = order.optString("id")
        if (id.isEmpty() || id == orderId) return
        serverOrder = order
        orderId = id
        invoice = order.optString("invoice")
        cart.clear()
        cart.addAll(readOrderItems(order))
        cartAdapter.submit(cart)
        sub.text = getString(R.string.tables_busy) + "  ·  #$invoice"
        Toast.makeText(
            this,
            "This table already has invoice #$invoice - adding to it",
            Toast.LENGTH_LONG
        ).show()
        paintTotals()
    }

    private fun buildChips() {
        cats.removeAllViews()
        val names = ArrayList<String>()
        names.add("")
        for (c in categories) names.add(c)
        if (currentCat !in names) currentCat = names[0]
        for (name in names) {
            val chip = Chip(this)
            chip.text = if (name.isEmpty()) "ALL" else name
            chip.isCheckable = true
            chip.textSize = 12f
            chip.setOnClickListener {
                currentCat = name
                paintChips(names)
                showMenu()
            }
            cats.addView(chip)
        }
        paintChips(names)
    }

    private fun paintChips(names: List<String>) {
        cats.clearCheck()
        for (i in 0 until cats.childCount) {
            val chip = cats.getChildAt(i) as? Chip ?: continue
            chip.isChecked = names[i] == currentCat
        }
    }

    /**
     * Show the dishes that match the category AND the search box.
     *
     * Both filters apply at once, exactly as on the till: picking a category
     * does not throw away what is already typed.
     */
    private fun showMenu() {
        val state = lastState ?: return
        items = readItems(state, currentCat, search)
        menuAdapter.submit(items)
        if (items.isEmpty()) {
            emptyText.setText(
                if (search.isEmpty()) R.string.order_empty else R.string.order_no_match
            )
            emptyText.visibility = View.VISIBLE
        } else {
            emptyText.visibility = View.GONE
        }
    }

    /* ---------------------------------------------------------- the cart */

    /**
     * One tap on a dish adds one.
     *
     * The merge test is the till's own: the same dish and no note on the
     * line.  It deliberately does NOT look at what the kitchen already has -
     * the till does not either, and for the same reason. If the cooks already
     * have 1 burger and the waiter taps burger again, both screens bump that
     * line to 2 and the next ticket prints just the +1. A second line for the
     * same dish would leave the kitchen holding two separate entries for one
     * table, which is how an order gets cooked twice.
     */
    private fun addLine(row: MenuRow) {
        val existing = cart.firstOrNull { it.itemId == row.id && it.note.isEmpty() }
        if (existing != null) {
            existing.qty += 1.0
        } else {
            cart.add(
                CartLine(
                    lineId = newLineId(),
                    itemId = row.id,
                    code = row.code,
                    name = row.name,
                    cat = row.cat,
                    price = row.price,
                    qty = 1.0,
                    sentQty = 0.0,
                    note = ""
                )
            )
        }
        cartAdapter.submit(cart)
        paintTotals()
    }

    private fun changeQty(line: CartLine, by: Double) {
        // A line already off the bill cannot go further DOWN - the till
        // blocks - for exactly the same reason, and says the same thing.
        //
        // Note the guard is only on the way down. "+" must still work, or a
        // line could never be put back - and the message below tells the
        // waiter to press +, which would be a button that does nothing.
        if (by < 0 && line.qty <= 0) {
            Toast.makeText(
                this,
                if (line.sentQty > 0) R.string.order_removed_back_kot
                else R.string.order_removed_back,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        setQty(line, line.qty + by)
    }

    /**
     * Set a line's quantity, and decide what zero means.
     *
     * This is the till's own setQty. Zero is not one thing:
     *
     *  * the kitchen has never been told  -> the line just goes. Nothing
     *    printed, nothing to undo, nobody is cooking it.
     *  * the kitchen already has it       -> the line STAYS on the bill at
     *    zero, marked in red. The next kitchen ticket prints a minus so the
     *    cooks take it away, and the stock maths (+1 then -1 = 0) keeps
     *    matching the food that really left.
     *
     * Throwing the line away at that point is the expensive mistake: the bill
     * says cancelled, the kitchen keeps cooking, and the table is charged for
     * food nobody sees.
     */
    private fun setQty(line: CartLine, qty: Double) {
        if (qty > 0) {
            line.qty = qty
            cartAdapter.submit(cart)
            paintTotals()
            return
        }
        if (line.sentQty > 0) {
            confirmRemove(line)
            return
        }
        cart.remove(line)          // never sent - it just goes
        cartAdapter.submit(cart)
        paintTotals()
    }

    /** The confirmation the till shows before a minus reaches the kitchen. */
    private fun confirmRemove(line: CartLine) {
        AlertDialog.Builder(this)
            .setTitle(R.string.order_remove_title)
            .setMessage(
                getString(R.string.order_remove_body, line.name, qtyText(line.sentQty))
            )
            .setPositiveButton(android.R.string.ok) { _, _ ->
                line.qty = 0.0
                cartAdapter.submit(cart)
                paintTotals()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Type the exact quantity: "4 ordered, I want 3" in one step.
     *
     * Tapping - four times to take one off a four is slow, and the road ends
     * at the removal confirmation, which reads as though it is cancelling all
     * four rather than one. This goes straight to the number the waiter
     * actually wants, then runs the SAME setQty the buttons use - so a zero
     * still asks first if the kitchen already has the food.
     */
    private fun askQty(line: CartLine) {
        val field = EditText(this)
        field.inputType =
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        field.setText(qtyText(line.qty))
        field.setSelection(field.text.length)
        field.hint = getString(R.string.order_qty_hint)
        field.setTextColor(resources.getColor(R.color.text, null))
        field.setHintTextColor(resources.getColor(R.color.text_dim, null))
        val pad = (20 * resources.displayMetrics.density).toInt()
        field.setPadding(pad, pad / 2, pad, pad / 2)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.order_qty_title, line.name))
            .setView(field)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val raw = field.text.toString().trim().replace(',', '.')
                val value = raw.toDoubleOrNull()
                // An empty or half-typed box must NOT become zero. Clearing
                // the box to retype is the normal way to edit, and reading it
                // as a zero would delete the line - or ask to remove food the
                // kitchen already has - which is the trap the till had too.
                if (value == null || !value.isFinite() || value < 0) {
                    Toast.makeText(this, R.string.order_qty_bad, Toast.LENGTH_SHORT)
                        .show()
                } else {
                    setQty(line, value)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeLine(line: CartLine) {
        // A live line goes through the same confirmation as "-", so the
        // kitchen still gets its minus. A line already at zero can only leave
        // once the cooks have been told about it.
        if (line.qty > 0) {
            setQty(line, 0.0)
            return
        }
        if (line.sentQty > 0) {
            Toast.makeText(
                this,
                getString(R.string.order_send_first, qtyText(line.sentQty)),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        cart.remove(line)
        cartAdapter.submit(cart)
        paintTotals()
    }

    private fun paintTotals() {
        total.text = "Rs " + money(cartTotal(cart))
        count.text = "${qtyText(cartCount(cart))} item(s)"
        cartHead.text =
            if (cart.isEmpty()) getString(R.string.order_cart_empty)
            else "${qtyText(cartCount(cart))} on the bill"
        save.isEnabled = cart.isNotEmpty() && !busy
        kitchen.isEnabled = cart.isNotEmpty() && hasUnsent(cart) && !busy
    }

    /* ------------------------------------------------------------ saving */

    private fun doSave() {
        if (cart.isEmpty()) {
            Toast.makeText(this, R.string.order_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (serverLost()) {
            Toast.makeText(this, R.string.offline_banner, Toast.LENGTH_LONG).show()
            return
        }
        runBusy(true)
        lifecycleScope.launch {
            try {
                val saved = saveOrder()
                if (!active) return@launch
                // the server's own answer is now the truth: keep it, so the
                // next save carries the same invoice and the same charges
                serverOrder = saved
                orderId = saved.optString("id", orderId)
                invoice = saved.optString("invoice", invoice)
                sub.text = "#$invoice"
                Toast.makeText(
                    this@OrderActivity,
                    "Order saved - invoice #$invoice",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                if (active) Toast.makeText(this@OrderActivity, e.message, Toast.LENGTH_LONG).show()
            } finally {
                runBusy(false)
            }
        }
    }

    /**
     * Create the order if this table had none, then put the cart on it.
     *
     * Both halves matter: the server only accepts items against an order that
     * already exists, and only an open one - so a bill closed on the till in
     * the last second is met with a clear refusal, never a half-written order.
     */
    private suspend fun saveOrder(): JSONObject {
        val api = PosApp.instance.api

        if (serverOrder == null) {
            val create = JSONObject()
                .put("type", "dinein")
                .put("tableId", tableId)
                .put("covers", 0)
                .put("customer", JSONObject())
            val res = api.actionOn("order.create", create)
            val created = res.optJSONObject("order")
                ?: throw PosApi.PosException("The main server did not create the order.")
            val already = res.optBoolean("existing", false) &&
                (created.optJSONArray("items")?.length() ?: 0) > 0
            serverOrder = created
            orderId = created.optString("id")
            invoice = created.optString("invoice")
            if (already) {
                // somebody got there first - carry on with their bill so the
                // table never ends up with two
                cart.clear()
                cart.addAll(readOrderItems(created))
                cartAdapter.submit(cart)
            }
        }

        val target = serverOrder ?: throw PosApi.PosException("No order for this table.")
        val payload = payloadFor(target)
        val res = api.actionOn(
            "order.save",
            JSONObject().put("orderId", orderId).put("order", payload)
        )
        return res.optJSONObject("order")
            ?: throw PosApi.PosException("The main server did not save the order.")
    }

    /**
     * The cart, wrapped in the server's own order.
     *
     * servicePercent, taxPercent, discount and the rest are copied straight
     * from the order the server gave us.  Sending zeros here would quietly
     * take the service charge and the tax off every bill in the restaurant,
     * so they are never re-invented on this phone.
     */
    private fun payloadFor(order: JSONObject): JSONObject {
        val out = JSONObject()
        out.put("id", order.optString("id", orderId))
        out.put("type", order.optString("type", "dinein"))
        out.put("tableId", tableId)
        out.put("tableName", tableName)
        out.put("covers", order.optInt("covers", 0))
        out.put("customer", order.optJSONObject("customer") ?: JSONObject())

        val rows = JSONArray()
        for (line in cart) {
            val row = JSONObject()
                .put("lineId", line.lineId)
                .put("itemId", line.itemId)
                .put("code", line.code)
                .put("name", line.name)
                .put("cat", line.cat)
                .put("price", line.price)
                .put("qty", line.qty)
                .put("sentQty", line.sentQty)
                .put("note", line.note)
            row.put("mods", JSONArray())
            row.put("parts", JSONArray())
            row.put("course", "")
            rows.put(row)
        }
        out.put("items", rows)
        out.put("kotNote", order.optString("kotNote", ""))

        out.put("discountType", order.optString("discountType", "amount"))
        out.put("discountValue", order.optDouble("discountValue", 0.0))
        out.put("servicePercent", order.optDouble("servicePercent", 0.0))
        out.put("serviceCharge", order.optDouble("serviceCharge", 0.0))
        out.put("taxPercent", order.optDouble("taxPercent", 0.0))
        out.put("deliveryCharge", order.optDouble("deliveryCharge", 0.0))
        out.put("driverId", order.optString("driverId", ""))
        out.put("waiterId", order.optString("waiterId", ""))
        out.put("tip", order.optDouble("tip", 0.0))
        return out
    }

    /* ------------------------------------------------------- the kitchen */

    private fun doKitchen() {
        if (cart.isEmpty()) {
            Toast.makeText(this, R.string.order_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (serverLost()) {
            Toast.makeText(this, R.string.offline_banner, Toast.LENGTH_LONG).show()
            return
        }
        runBusy(true)
        lifecycleScope.launch {
            try {
                // save first, exactly like the till: a KOT must always match
                // a bill that really exists
                val saved = saveOrder()
                serverOrder = saved
                orderId = saved.optString("id", orderId)
                invoice = saved.optString("invoice", invoice)

                val res = PosApp.instance.api.actionOn(
                    "order.kot",
                    JSONObject().put("orderId", orderId).put("note", "")
                )
                if (!active) return@launch
                val kot = res.optJSONObject("kot")
                val number = kot?.optInt("no", 0) ?: 0
                val warning = res.optString("warning")
                val text = if (warning.isNotEmpty()) {
                    "KOT #$number recorded - printer problem: $warning"
                } else {
                    "KOT #$number sent to the kitchen"
                }
                Toast.makeText(this@OrderActivity, text, Toast.LENGTH_LONG).show()
                load()                 // the kitchen stamp comes back on the lines
            } catch (e: Exception) {
                if (active) Toast.makeText(this@OrderActivity, e.message, Toast.LENGTH_LONG).show()
            } finally {
                runBusy(false)
            }
        }
    }

    /* --------------------------------------------------------- plumbing */

    private fun runBusy(on: Boolean) {
        busy = on
        save.isEnabled = !on && cart.isNotEmpty()
        kitchen.isEnabled = !on && cart.isNotEmpty() && hasUnsent(cart)
        sub.text = when {
            on -> getString(R.string.server_searching)
            invoice.isEmpty() -> getString(R.string.order_empty)
            else -> "#$invoice"
        }
    }

    /**
     * Has the main server been lost?
     *
     * A dropped connection is lost at once.  A cold start is not - the socket
     * takes a moment, and crying wolf there would only train the waiter to
     * ignore the banner.
     */
    private fun serverLost(): Boolean {
        if (connected) return false
        if (everConnected) return true
        if (!connectFailed) return false
        return System.currentTimeMillis() - startedAt > 4000
    }

    private fun paintLink() {
        val lost = serverLost()
        banner.visibility = if (lost) View.VISIBLE else View.GONE
        dot.setText(if (lost) R.string.link_offline else R.string.link_live)
        dot.setBackgroundResource(if (lost) R.drawable.pill_red else R.drawable.pill_green)
        if (!lost && invoice.isNotEmpty()) sub.text = "#$invoice"
    }

    override fun onState(state: JSONObject, rev: Long) {
        lastState = state
        if (!active) return
        if (items.isEmpty() || categories.isEmpty()) {
            categories = readCategories(state)
            buildChips()
            showMenu()
        }
        val running = readOpenOrder(state, tableId)
        if (running != null && running.optString("id") != orderId) adopt(running)
        paintTotals()
    }

    override fun onConnection(up: Boolean) {
        connected = up
        if (up) everConnected = true else connectFailed = true
        if (active) paintLink()
    }

    companion object {
        const val EXTRA_TABLE_ID = "tableId"
        const val EXTRA_TABLE_NAME = "tableName"

        /** How long the search box waits before it filters the menu. */
        const val SEARCH_DEBOUNCE_MS = 150L
    }
}
