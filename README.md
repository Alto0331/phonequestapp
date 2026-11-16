# Quest-Mirror

## Monorepo

Structure du projet :
```
quest-mirror/
├── phone/          # Appli compagnon Android (Samsung S23 Ultra)
├── quest/          # Appli XR Meta Quest 3
├── core-rust/      # Crate Rust client scrcpy
└── docs/           # Documentation (SPEC.md, décisions, etc.)
```

## Objectif
Afficher l'écran du Samsung Galaxy S23 Ultra sur Meta Quest 3 en écran flottant passthrough, avec contrôle complet depuis le casque.

## Phases
- **✅ V0** : Pipeline de base - flux vidéo visible dans Quest (EN COURS)
- **⏳ V1** : Réalité mixte - quad flottant + mains
- **⏳ V2** : Contrôle - tactile + clavier
- **⏳ Finitions** : QR, avertissement Wi-Fi, heartbeat, etc.

## Build & Test

### Étape 1 : Squelette du repo (✅ VALIDÉ)
Vérifier que tout compile :

```bash
# Build projets Android (phone + quest)
cd quest-mirror
./gradlew :phone:assembleDebug :quest:assembleDebug

# Build crate Rust pour Android
cd core-rust
cargo build --target aarch64-linux-android
```

### Étape 2 : Pipeline V0 (🔄 EN COURS)

#### phone/ : Lancement de scrcpy-server
- `scrcpy-server.jar` (v5.0.1) dans `phone/src/main/assets/`
- `ServerManager.kt` lance le serveur sur port **27183**
- Affiche l'état (IP:port) dans `MainActivity`

#### core-rust/ : Client scrcpy
- Connexion TCP vers le téléphone
- Réception du flux H.265 brut
- Bibliothèque : `libquest_mirror_core.so`

#### quest/ : Décodage et affichage
- `VideoRenderer.kt` avec MediaCodec HEVC
- SurfaceView pour l'affichage 2D
- Intégration JNI avec le crate Rust

**Commandes de test V0** :
```bash
# 1. Installer l'appli phone sur le S23 Ultra
adb install phone/build/outputs/apk/debug/phone-debug.apk

# 2. Lancer l'appli phone (affiche IP:27183)

# 3. Installer l'appli quest sur le Quest 3
adb install quest/build/outputs/apk/debug/quest-debug.apk

# 4. Lancer l'appli quest (se connecte à IP:27183)
#    → Doit afficher le flux vidéo du téléphone
```

## Environnement requis
- Windows + Android Studio
- NDK r26d + CMake
- JDK 17+
- Meta Quest 3 en mode dev (adb USB OK)
- S23 Ultra (adb tcpip 5555)

## Architecture

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Phone (Kotlin)│────▶│   Core-Rust     │     │   Quest (Kotlin)│
│   (S23 Ultra)   │     │   (JNI/cdylib)  │     │   (Quest 3)     │
└────────┬────────┘     └────────┬────────┘     └────────┬────────┘
         │                       │                        │
         ▼                       ▼                        ▼
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│ scrcpy-server   │     │  Flux H.265 brut │     │  MediaCodec     │
│ (local:27183)   │────▶│  (socket TCP)   │────▶│  HEVC matériel  │
└─────────────────┘     └─────────────────┘     │  + SurfaceView  │
                                                 └─────────────────┘
```

## Documentation
- [SPEC.md](docs/SPEC.md) - Cahier des charges complet
- [phone/README.md](phone/README.md) - Appli compagnon
- [quest/README.md](quest/README.md) - Appli XR
- [core-rust/README.md](core-rust/README.md) - Crate Rust

## Décisions
- V0 utilise une connexion TCP directe sans handshake scrcpy complet
- Le décodage vidéo se fait côté Quest (MediaCodec)
- Le crate Rust gère la connexion et la réception du flux

## Règles de travail
1. Étapes courtes et compilables
2. Validation entre chaque étape
3. Commandes exactes + résultat de test attendu
4. Commentaires en français, identifiants en anglais
