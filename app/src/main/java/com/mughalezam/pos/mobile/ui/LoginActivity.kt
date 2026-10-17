package com.mughalezam.pos.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.mughalezam.pos.mobile.PosApp
import com.mughalezam.pos.mobile.R
import com.mughalezam.pos.mobile.util.Prefs
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Screen 2 - who is holding the phone.
 *
 * The password is checked by the MAIN SERVER, not here: this screen just asks
 * and repeats the answer, so the phone never keeps a password and cannot let
 * anybody in that the main PC would refuse.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var go: MaterialButton
    private lateinit var again: MaterialButton
    private lateinit var busy: ProgressBar
    private lateinit var status: TextView
    private lateinit var serverLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        user = findViewById(R.id.loginUser)
        pass = findViewById(R.id.loginPass)
        go = findViewById(R.id.loginGo)
        again = findViewById(R.id.loginAgain)
        busy = findViewById(R.id.loginBusy)
        status = findViewById(R.id.loginStatus)
        serverLabel = findViewById(R.id.loginServer)

        findViewById<ImageView>(R.id.loginLogo).setImageResource(R.drawable.ic_launcher)

        val api = PosApp.instance.api
        serverLabel.text = api.server()

        // whoever signed in last time is offered first, the name only
        val last = Prefs.username(this)
        if (last.isNotEmpty()) user.setText(last)

        go.setOnClickListener { signIn() }
        again.setOnClickListener {
            startActivity(Intent(this, ServerActivity::class.java))
            finish()
        }
    }

    private fun signIn() {
        val name = user.text?.toString()?.trim() ?: ""
        val secret = pass.text?.toString() ?: ""
        if (name.isEmpty()) {
            status.text = getString(R.string.login_user)
            return
        }
        if (secret.isEmpty()) {
            status.text = getString(R.string.login_pass)
            return
        }

        status.text = ""
        busy.visibility = View.VISIBLE
        go.isEnabled = false
        again.isEnabled = false

        lifecycleScope.launch {
            val api = PosApp.instance.api
            try {
                api.user = name
                val payload = JSONObject()
                    .put("username", name)
                    .put("password", secret)
                val res = api.actionOn("login", payload)

                val row: JSONObject = res.optJSONObject("user") ?: JSONObject()
                val perms = res.optJSONArray("perms")
                val held = ArrayList<String>()
                if (perms != null) {
                    for (i in 0 until perms.length()) {
                        val p = perms.optString(i)
                        if (p.isNotEmpty()) held.add(p)
                    }
                }

                val username = row.optString("username", name)
                val display = row.optString("name", username)

                Prefs.saveUser(this@LoginActivity, username, display, held.joinToString(","))
                api.user = username

                pass.setText("")
                startActivity(Intent(this@LoginActivity, TablesActivity::class.java))
                finish()
            } catch (e: Exception) {
                // the server's own words - "Wrong password.", "User not found."
                status.text = e.message ?: "Could not sign in."
                Toast.makeText(this@LoginActivity, e.message, Toast.LENGTH_SHORT).show()
            } finally {
                busy.visibility = View.GONE
                go.isEnabled = true
                again.isEnabled = true
            }
        }
    }
}
