# Quest-Mirror Quest (Appli XR)

## Description
Appli pour Meta Quest 3 qui:
- Reçoit le flux vidéo H.265 du téléphone
- Décode avec MediaCodec matériel
- Affiche dans une SurfaceView (2D pour V0)

## Configuration requise
- Meta Quest 3 en mode développeur
- Android 17+
- Librairie native : `libquest_mirror_core.so` (compilée avec cargo-ndk)

## Build
```bash
cd quest
./gradlew assembleDebug
```

## Installation
```bash
adb install quest/build/outputs/apk/debug/quest-debug.apk
```

## Structure
- `MainActivity.kt` : Point d'entrée, crée VideoRenderer
- `VideoRenderer.kt` : Gère MediaCodec + SurfaceView + connexion TCP
- `jni/Core.kt` : Interface JNI avec le crate Rust

## Fonctionnalités implémentées (V0)
- [x] SurfaceView pour affichage vidéo
- [x] MediaCodec HEVC (H.265) matériel
- [x] Connexion TCP directe au téléphone
- [x] Intégration de libquest_mirror_core.so

## À implémenter (V1+)
- [ ] OpenXR/Meta XR SDK
- [ ] Quad flottant en passthrough
- [ ] Interaction mains (déplacement/redimensionnement)
- [ ] Événements tactiles vers scrcpy
- [ ] Clavier système Quest
- [ ] QR code pour la connexion
- [ ] Overlay debug (fps/latence)

## Dépendances
- AndroidX Core
- AndroidX AppCompat
- MediaCodec (API Android)
- Crate Rust : quest-mirror-core (JNI)
