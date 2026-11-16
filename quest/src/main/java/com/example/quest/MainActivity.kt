package com.example.quest

import android.content.Intent
import android.os.Bundle
import android.app.Activity

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Récupérer l'IP et le PORT des extras de l'intent
        val ip = intent.getStringExtra("IP") ?: "192.168.1.156"
        val port = intent.getIntExtra("PORT", 27183)
        
        // Démarrer XrActivity avec les bons paramètres
        val intent = Intent(this, XrActivity::class.java).apply {
            putExtra("IP", ip)
            putExtra("PORT", port)
        }
        startActivity(intent)
        finish()
    }
}
