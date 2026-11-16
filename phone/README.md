# Quest-Mirror Phone (Appli Compagnon)

## Description
Appli Android companion pour Samsung Galaxy S23 Ultra qui:
- Embarque `scrcpy-server.jar` dans les assets
- Lance le serveur scrcpy en processus local sur port fixe **27183**
- Affiche l'état du serveur, IP:port

## Configuration requise
- Android 17 (API 34) - Samsung Galaxy S23 Ultra
- Wi-Fi 5 GHz recommandé (2.4 GHz détecté et averti)

## Build
```bash
cd phone
./gradlew assembleDebug
```

## Installation
```bash
adb install phone/build/outputs/apk/debug/phone-debug.apk
```

## Structure
- `ServerManager.kt` : Gère le lancement de scrcpy-server.jar
- `MainActivity.kt` : Interface simple affichant l'état

## Fonctionnalités implémentées (V0)
- [x] Embed scrcpy-server.jar (v5.0.1) dans assets
- [x] Lancement du serveur via ProcessBuilder
- [x] Port fixe 27183
- [x] Affichage IP:port

## À implémenter
- [ ] Détection bande Wi-Fi (2.4 GHz)
- [ ] Heartbeat: micro-overlay 1x1px
- [ ] Détection fluidité mouvement Adaptatif
- [ ] Exclusion optimisation batterie
- [ ] QR code pour la connexion

## Dépendances
- AndroidX Core
- AndroidX AppCompat
