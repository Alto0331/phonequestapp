package com.example.quest

/**
 * Lien entre la fenêtre du téléphone (XrActivity) et le panneau de commandes séparé
 * (VolumeActivity) : même processus, donc de simples rappels suffisent.
 */
object MirrorControls {
    /** Retour / Accueil / Récents (VideoRenderer.GLOBAL_*). */
    var sendGlobal: ((Int) -> Unit)? = null
    /** Bascule écran du téléphone éteint/allumé. */
    var toggleScreen: (() -> Unit)? = null
    /** Ouvre le panneau DeX. */
    var openDex: (() -> Unit)? = null

    /** État courant de la dalle du téléphone (true = éteinte). */
    @Volatile var screenOff = false
        private set
    /** Prévenu (thread UI) quand l'état de la dalle change. */
    var onScreenStateChanged: ((Boolean) -> Unit)? = null

    fun setScreenOff(off: Boolean) {
        screenOff = off
        onScreenStateChanged?.invoke(off)
    }

    fun clear() {
        sendGlobal = null
        toggleScreen = null
        openDex = null
        setScreenOff(false)
    }
}
