#!/bin/sh
# Wrapper pour le linker Android NDK
# Ce script est appelé par cargo/rustc pour cross-compiler vers aarch64-linux-android

NDK_HOME="C:/Tools/android-ndk/android-ndk-r26d"
TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/windows-x86_64/bin"

# Utiliser aarch64-linux-android21-clang (API level 21 minimum)
"$TOOLCHAIN/aarch64-linux-android21-clang" "$@"
