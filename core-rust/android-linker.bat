@echo off
setlocal

set NDK_HOME=C:\Tools\android-ndk\android-ndk-r26d
set TOOLCHAIN=%NDK_HOME%\toolchains\llvm\prebuilt\windows-x86_64\bin

rem Utiliser aarch64-linux-android21-clang (API level 21 minimum)
%TOOLCHAIN%\aarch64-linux-android21-clang %*
