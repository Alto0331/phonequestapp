# Quest-Mirror Core (Crate Rust)

## Description
Crate Rust implémentant le client scrcpy pour la communication entre Quest et téléphone.

### Responsabilités
- Connexion socket TCP vers `scrcpy-server.jar` (port 27183)
- Handshake protocole scrcpy
- Réception du flux H.265 brut
- Intégration JNI avec Android

### Architecture
- **Entrée** : Socket TCP connectée à scrcpy-server
- **Sortie** : Flux H.265 brut (via canal mpsc)
- **Contrôle** : Événements encodés (à implémenter)

⚠️ **Le décodage vidéo NE SE FAIT PAS en Rust**
Le crate livre le flux brut. Le Quest décode via MediaCodec HEVC matériel.

## Build

### Prérequis
- [Rust](https://rust-lang.org) (avec cible aarch64-linux-android)
- [NDK Android r26d](https://developer.android.com/ndk)
- [cargo-ndk](https://github.com/rust-windowing/android-ndk-rs) (optionnel)

### Avec cargo-ndk (recommandé)
```bash
cd core-rust
cargo ndk -t aarch64-linux-android build --release
```

### Avec NDK manuel
```bash
# Configurer les variables d'environnement
NDK_HOME=/chemin/vers/android-ndk-r26d
cargo build --target aarch64-linux-android
```

### Sortie
- Bibliothèque : `target/aarch64-linux-android/release/libquest_mirror_core.so`

## Intégration Android

### Copier la bibliothèque
```bash
# Créer le dossier jniLibs
mkdir -p quest/src/main/jniLibs/arm64-v8a

# Copier la bibliothèque
cp target/aarch64-linux-android/release/libquest_mirror_core.so \
   quest/src/main/jniLibs/arm64-v8a/
```

### Charger dans Gradle
La bibliothèque est automatiquement incluse dans l'APK si placée dans `src/main/jniLibs/<abi>/`.

## Intégration JNI

### Charger la bibliothèque
```kotlin
// Dans Core.kt
init {
    System.loadLibrary("quest_mirror_core")
}
```

### Fonctions exposées
```kotlin
// Initialisation
external fun initClient(): Long
external fun releaseClient(clientPtr: Long)

// Connexion
external fun connect(clientPtr: Long, ip: String, port: Int): Boolean
external fun startVideo(clientPtr: Long)
external fun disconnect(clientPtr: Long)
```

## Protocole scrcpy

### Messages implémentés (V0)
- Connexion TCP brute (sans handshake complet)
- Réception flux brut

### Messages à implémenter
- [ ] DeviceInfo (0x00) - Handshake
- [ ] DeviceSize (0x02) - Taille écran
- [ ] VideoStream (0x18) - Configuration codec
- [ ] VideoPacket (0x19) - Paquets vidéo
- [ ] ControlMsg - Événements de contrôle

### Référence
- [scrcpy PROTOCOL.md](https://github.com/Genymobile/scrcpy/blob/master/doc/PROTOCOL.md)

## Décisions
- V0 : Connexion TCP directe sans handshake complet pour simplifier
- Le décodage reste côté Quest (MediaCodec)
- Le crate Rust gère uniquement la connexion et la réception
