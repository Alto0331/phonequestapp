package com.example.quest

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Panneau de commandes séparé (tâche à part → Horizon OS l'affiche à côté de la fenêtre du
 * téléphone) : Retour/Accueil/Récents, écran du téléphone, DeX et volume du casque.
 * La fenêtre du téléphone n'a ainsi aucun bouton et garde exactement le format de l'écran.
 */
class VolumeActivity : Activity() {
    companion object {
        @Volatile var instance: VolumeActivity? = null
            private set
    }

    private lateinit var slider: VerticalSlider
    private lateinit var label: TextView
    private lateinit var muteButton: Button
    private lateinit var screenButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        setContentView(R.layout.activity_volume)
        VolumeSettings.load(this)

        slider = findViewById(R.id.volume_slider)
        label = findViewById(R.id.volume_label)
        muteButton = findViewById(R.id.mute_button)

        slider.value = VolumeSettings.volume
        slider.onValueChanged = { v ->
            VolumeSettings.setVolume(this, v)
            refresh()
        }
        muteButton.setOnClickListener {
            VolumeSettings.setMuted(this, !VolumeSettings.muted)
            refresh()
        }

        findViewById<View>(R.id.button_back).setOnClickListener { MirrorControls.sendGlobal?.invoke(VideoRenderer.GLOBAL_BACK) }
        findViewById<View>(R.id.button_home).setOnClickListener { MirrorControls.sendGlobal?.invoke(VideoRenderer.GLOBAL_HOME) }
        findViewById<View>(R.id.button_recents).setOnClickListener { MirrorControls.sendGlobal?.invoke(VideoRenderer.GLOBAL_RECENTS) }
        findViewById<View>(R.id.button_dex).setOnClickListener { MirrorControls.openDex?.invoke() }
        screenButton = findViewById(R.id.button_screen)
        screenButton.setOnClickListener { MirrorControls.toggleScreen?.invoke() }
        MirrorControls.onScreenStateChanged = { off -> runOnUiThread { refreshScreen(off) } }
        refreshScreen(MirrorControls.screenOff)
        refresh()
    }

    /** ☀ = écran du téléphone allumé ; ● = dalle éteinte (le miroir continue). */
    private fun refreshScreen(off: Boolean) {
        screenButton.text = if (off) "●" else "☀"
    }

    private fun refresh() {
        label.text = if (VolumeSettings.muted) "Muet" else "${(VolumeSettings.volume * 100).roundToInt()} %"
        muteButton.text = if (VolumeSettings.muted) "🔇" else "🔊"
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            MirrorControls.onScreenStateChanged = null
        }
        super.onDestroy()
    }
}
