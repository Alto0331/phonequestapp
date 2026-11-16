Tu es mon agent de code pour une application personnelle, usage exclusif de mes deux appareils. Zéro compatibilité requise au-delà.

Objectif

Afficher l'écran de mon Samsung Galaxy S23 Ultra (Android 17, One UI) sur mon Meta Quest 3 en écran flottant passthrough, déplaçable/redimensionnable aux mains, avec contrôle complet du téléphone depuis le casque (toucher + texte via clavier système Quest).

Architecture

2.1 Côté téléphone — appli compagnon (Kotlin) — embarque scrcpy-server.jar (assets) et le lance elle-même en processus local : aucun adb à l'usage. Port fixe (ex. 27183). Encodage H.265 MediaCodec matériel, ~10-12 Mbps, max-size 1920, 60 fps ; H.264 en option repli. Écran : état du serveur, IP:port, QR code. Exigences issues de tests réels :





Heartbeat : micro-overlay animée 1×1 px qui force l'encodage en continu (sinon 0 fps sur écran statique et reprise laggy).



Pas d'affichage virtuel pour le miroir : capturer l'écran réel (Samsung intercepte les virtual displays et lance DeX). Mode « DeX flottant » = bonus future, ne pas implémenter.



Détecter bande Wi-Fi : avertir si 2,4 GHz. Documenter mode hotspot 5 GHz (Quest connecté au hotspot du téléphone).



Exclure l'appli de l'optimisation batterie + guider l'utilisateur.



Détecter « fluidité du mouvement » Adaptatif et recommander Standard 60 Hz.

2.2 Cœur Rust — crate client scrcpy — connexion socket, handshake, parsing métadonnées, réception flux H.265 brut, encodage des événements de contrôle (toucher, texte). Protocole documenté dans le repo github.com/Genymobile/scrcpy (doc/). Build via cargo-ndk, cible aarch64-linux-android, exposé en JNI. Le décodage vidéo ne se fait PAS en Rust : livrer le flux brut, le Quest décode en MediaCodec.

2.3 Côté Quest (Kotlin + OpenXR/Meta XR SDK) — appli immersive passthrough, quad flottant avec SurfaceTexture alimentée par MediaCodec HEVC matériel. Interaction SDK : déplacer/redimensionner au pincement des mains ; position/échelle persistées entre sessions. Toucher le quad → événement tactile scrcpy (coordonnées converties). Clavier système Quest → événement texte scrcpy. Écran de connexion IP:port + QR, overlay debug fps/latence activable.

Phases (ordre strict, validation entre chaque)





V0 — pipeline : crate Rust + appli Quest 2D plate affichant le flux. Validation : écran du téléphone visible dans une fenêtre du Quest, ~60 fps sur contenu animé.



V1 — MR : passthrough + quad flottant + mains. Validation : placement libre, stable 1 h en passthrough.



V2 — contrôle : tactile + clavier. Validation : naviguer les applis et écrire un SMS depuis le casque.



Finitions : QR, avertissement Wi-Fi, heartbeat, hotspot, réglages codec/bitrate/fps, debug overlay.

Environnement

Windows + Android Studio + NDK/CMake + cargo-ndk (cible aarch64-linux-android). Quest 3 en mode dev, adb USB OK (série 2G97C5ZHCC01BF). S23 Ultra en adb tcpip 5555 (l'appairage sans fil Android est capricieux, ne pas s'appuyer dessus). scrcpy 5.0.1 sur PC comme référence (pipeline H.265 1080×1920@60 validé en Wi-Fi). Install via adb install uniquement.

Règles de travail





Étapes courtes compilables ; à chaque étape : commandes exactes (gradle/adb/cargo) + résultat de test attendu.



Décisions non triviales justifiées une phrase dans docs/.



Toute dépendance en dehors de Meta XR SDK/MediaCodec/JNI : me demander d'abord.



Commentaires en français, identifiants en anglais.