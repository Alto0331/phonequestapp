package com.example.quest

import android.os.Bundle

/** Panneau séparé affichant le bureau Samsung DeX (flux du programme d'aide, port 27184). */
class DexActivity : XrActivity() {
    companion object {
        @Volatile var instance: DexActivity? = null
            private set
    }

    override val dexMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        instance = this
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
