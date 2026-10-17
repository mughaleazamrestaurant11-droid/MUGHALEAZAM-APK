package com.mughalezam.pos.mobile.util

import android.content.Context

/**
 * What THIS phone remembers: the address of the main server and who signed
 * in on it.  Nothing else is stored here - no bill, no menu, no customer.
 * The phone is a window, not a second database, so if it is lost or wiped
 * nothing of the restaurant goes with it.
 */
object Prefs {

    private const val FILE = "pos_mobile"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var server: String = ""        // in-memory copy, so screens need no context
        private set

    fun load(context: Context): String {
        server = sp(context).getString("server", "") ?: ""
        return server
    }

    fun saveServer(context: Context, value: String) {
        server = value
        sp(context).edit().putString("server", value).apply()
    }

    fun username(context: Context): String =
        sp(context).getString("username", "") ?: ""

    fun displayName(context: Context): String =
        sp(context).getString("displayName", "") ?: ""

    /** Comma separated - permissions are short words and never contain a comma. */
    fun perms(context: Context): String =
        sp(context).getString("perms", "") ?: ""

    fun hasPerm(context: Context, perm: String): Boolean =
        perms(context).split(",").contains(perm)

    fun saveUser(context: Context, username: String, displayName: String, perms: String) {
        sp(context).edit()
            .putString("username", username)
            .putString("displayName", displayName)
            .putString("perms", perms)
            .apply()
    }

    fun forgetUser(context: Context) {
        sp(context).edit()
            .remove("username")
            .remove("displayName")
            .remove("perms")
            .apply()
    }
}
