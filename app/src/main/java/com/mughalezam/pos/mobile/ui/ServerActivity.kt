package com.mughalezam.pos.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.mughalezam.pos.mobile.PosApp
import com.mughalezam.pos.mobile.R
import com.mughalezam.pos.mobile.api.PosApi
import com.mughalezam.pos.mobile.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen 1 - which main server this phone belongs to.
 *
 * The shop's wi-fi router is the only thing in between: this phone finds the
 * main PC by walking its own network, or the address can be typed once and it
 * is remembered.  It never stores a menu or an order - there is nothing here
 * to lose if the phone is swapped for another.
 */
class ServerActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var find: MaterialButton
    private lateinit var go: MaterialButton
    private lateinit var busy: ProgressBar
    private lateinit var list: RecyclerView

    private val found = ArrayList<String>()
    private lateinit var adapter: ServerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server)

        input = findViewById(R.id.serverInput)
        status = findViewById(R.id.serverStatus)
        find = findViewById(R.id.serverFind)
        go = findViewById(R.id.serverGo)
        busy = findViewById(R.id.serverBusy)
        list = findViewById(R.id.serverList)

        val logo = findViewById<ImageView>(R.id.serverLogo)
        logo.setImageResource(R.drawable.ic_launcher)

        adapter = ServerAdapter(found) { url ->
            input.setText(url)
            input.setSelection(url.length)
            status.text = ""
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        // the address this phone used last time, so a daily start is one tap
        val saved = Prefs.load(this)
        if (saved.isNotEmpty()) input.setText(saved)

        find.setOnClickListener { search() }
        go.setOnClickListener { continueOn() }
    }

    private fun search() {
        find.isEnabled = false
        busy.visibility = View.VISIBLE
        status.text = getString(R.string.server_searching)
        found.clear()
        adapter.notifyDataSetChanged()

        lifecycleScope.launch {
            try {
                val urls = PosApp.instance.api.discover(PosApi.DEFAULT_PORT)
                found.clear()
                found.addAll(urls)
                adapter.notifyDataSetChanged()
                status.text = when {
                    found.isEmpty() -> getString(R.string.server_none)
                    found.size == 1 -> getString(R.string.server_found, 1)
                    else -> getString(R.string.server_found, found.size)
                }
                // one server found and nothing typed yet - take it
                if (found.size == 1 && input.text.isNullOrBlank()) {
                    input.setText(found[0])
                }
            } catch (e: Exception) {
                status.text = getString(R.string.server_none)
            } finally {
                busy.visibility = View.GONE
                find.isEnabled = true
            }
        }
    }

    private fun continueOn() {
        var url = PosApi.normalize(input.text?.toString() ?: "")
        if (url.isEmpty()) {
            status.text = getString(R.string.server_hint)
            return
        }
        // "192.168.10.50" is a common way to say it - add the usual port
        url = PosApi.withPort(url, PosApi.DEFAULT_PORT)

        go.isEnabled = false
        find.isEnabled = false
        busy.visibility = View.VISIBLE
        status.text = ""

        lifecycleScope.launch {
            val api = PosApp.instance.api
            try {
                api.setServer(url)
                val ok = withContext(Dispatchers.IO) { api.isReachable() }
                if (!ok) {
                    // say so plainly instead of opening a screen that only
                    // fails later - the main PC may simply be switched off
                    status.text = getString(R.string.server_none)
                    return@launch
                }
                Prefs.saveServer(this@ServerActivity, url)
                api.user = Prefs.username(this@ServerActivity)
                startActivity(Intent(this@ServerActivity, LoginActivity::class.java))
            } catch (e: Exception) {
                status.text = e.message ?: getString(R.string.server_none)
            } finally {
                busy.visibility = View.GONE
                go.isEnabled = true
                find.isEnabled = true
            }
        }
    }
}
