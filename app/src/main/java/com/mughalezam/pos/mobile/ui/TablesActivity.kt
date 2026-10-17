package com.mughalezam.pos.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.mughalezam.pos.mobile.PosApp
import com.mughalezam.pos.mobile.R
import com.mughalezam.pos.mobile.api.PosApi
import com.mughalezam.pos.mobile.api.TableRow
import com.mughalezam.pos.mobile.api.readTables
import com.mughalezam.pos.mobile.util.Prefs
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Screen 3 - which table the waiter is walking to.
 *
 * Everything here is the main server's own data, pulled once and then kept
 * live through the socket.  If the main PC goes off mid-shift the screen keeps
 * the last picture it had and says so in red - it never pretends the floor is
 * empty and it never lets a table be opened on a guess.
 */
class TablesActivity : AppCompatActivity(), PosApi.Listener {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var banner: TextView
    private lateinit var sections: ChipGroup
    private lateinit var who: TextView

    private var all: List<TableRow> = emptyList()
    private var busy: Set<String> = emptySet()
    private var currentSection: String = ""
    private var chipNames: List<String> = emptyList()
    private lateinit var adapter: TableAdapter

    private var active = false
    private var everConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tables)

        list = findViewById(R.id.tablesList)
        empty = findViewById(R.id.tablesEmpty)
        banner = findViewById(R.id.offlineBanner)
        sections = findViewById(R.id.tablesSections)
        who = findViewById(R.id.tablesWho)

        who.text = getString(R.string.tables_signed, Prefs.displayName(this))

        adapter = TableAdapter(emptyList(), emptySet()) { row ->
            val intent = Intent(this, OrderActivity::class.java)
            intent.putExtra(OrderActivity.EXTRA_TABLE_ID, row.id)
            intent.putExtra(OrderActivity.EXTRA_TABLE_NAME, row.name)
            startActivity(intent)
        }
        list.layoutManager = GridLayoutManager(this, 3)
        list.adapter = adapter

        findViewById<MaterialButton>(R.id.tablesRefresh).setOnClickListener { load() }
        findViewById<MaterialButton>(R.id.tablesSignOut).setOnClickListener { signOut() }

        banner.setOnClickListener { retry() }

        // the shared connection, opened once for the whole app
        PosApp.instance.api.connectSocket()
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

    /* ------------------------------------------------------------- loading */

    private fun load() {
        lifecycleScope.launch {
            try {
                val state = PosApp.instance.api.stateOn()
                if (!active) return@launch
                applyState(state)
            } catch (e: Exception) {
                if (!active) return@launch
                empty.visibility = View.VISIBLE
                list.visibility = View.GONE
                empty.text = e.message ?: getString(R.string.offline_banner)
            }
        }
    }

    private fun applyState(state: JSONObject) {
        all = readTables(state)
        busy = busyTables(state)

        val names = ArrayList<String>()
        for (t in all) if (t.section.isNotEmpty() && !names.contains(t.section)) names.add(t.section)
        if (names != chipNames) {
            chipNames = names
            buildChips()
        }

        list.visibility = View.VISIBLE
        empty.visibility = View.GONE
        filter()
    }

    private fun filter() {
        val rows = if (currentSection.isEmpty()) all
                   else all.filter { it.section == currentSection }
        adapter.submit(rows, busy)
        if (rows.isEmpty()) {
            empty.visibility = View.VISIBLE
            list.visibility = View.GONE
            empty.text = getString(R.string.tables_title)
        } else {
            empty.visibility = View.GONE
            list.visibility = View.VISIBLE
        }
    }

    private fun busyTables(state: JSONObject): Set<String> {
        val out = HashSet<String>()
        val arr = state.optJSONArray("orders") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("status") != "open") continue
            val tid = o.opt("tableId")
            if (tid is String && tid.isNotEmpty()) out.add(tid)
        }
        return out
    }

    /* -------------------------------------------------------- section row */

    private fun buildChips() {
        sections.removeAllViews()
        val names = ArrayList<String>()
        names.add("")                       // "everything"
        names.addAll(chipNames)
        currentSection = names[0]
        for (name in names) {
            val chip = Chip(this)
            chip.text = if (name.isEmpty()) "ALL" else name
            chip.isCheckable = true
            chip.textSize = 12f
            chip.setOnClickListener {
                currentSection = name
                paintChips(names)
                filter()
            }
            sections.addView(chip)
        }
        paintChips(names)
    }

    private fun paintChips(names: List<String>) {
        sections.clearCheck()
        for (i in 0 until sections.childCount) {
            val chip = sections.getChildAt(i) as? Chip ?: continue
            chip.isChecked = names[i] == currentSection
        }
    }

    /* ------------------------------------------------- live from the server */

    override fun onState(state: JSONObject, rev: Long) {
        if (!active) return
        applyState(state)
    }

    override fun onConnection(connected: Boolean) {
        if (!active) return
        if (connected) everConnected = true
        paintBanner(connected)
    }

    private fun paintBanner(connected: Boolean) {
        // never cry wolf on a cold start, before the socket has even tried
        banner.visibility =
            if (!connected && everConnected) View.VISIBLE else View.GONE
    }

    private fun retry() {
        PosApp.instance.api.connectSocket()
        load()
    }

    private fun signOut() {
        val api = PosApp.instance.api
        Prefs.forgetUser(this)
        api.user = ""
        Toast.makeText(this, R.string.tables_signout, Toast.LENGTH_SHORT).show()
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
