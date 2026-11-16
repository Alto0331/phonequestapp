package com.example.quest

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Point d'entrée : transmet IP/port à XrActivity et mémorise la dernière IP utilisée.
 * adb shell am start -n com.example.quest/.MainActivity --es IP 192.168.1.156 --ei PORT 27183
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("connection", MODE_PRIVATE)
        val ip = intent.getStringExtra(XrActivity.EXTRA_IP)
            ?: prefs.getString(XrActivity.EXTRA_IP, XrActivity.DEFAULT_IP)!!
        val port = if (intent.hasExtra(XrActivity.EXTRA_PORT)) intent.getIntExtra(XrActivity.EXTRA_PORT, XrActivity.DEFAULT_PORT)
            else prefs.getInt(XrActivity.EXTRA_PORT, XrActivity.DEFAULT_PORT)
        prefs.edit().putString(XrActivity.EXTRA_IP, ip).putInt(XrActivity.EXTRA_PORT, port).apply()

        startActivity(Intent(this, XrActivity::class.java).apply {
            putExtra(XrActivity.EXTRA_IP, ip)
            putExtra(XrActivity.EXTRA_PORT, port)
        })
        finish()
    }
}
