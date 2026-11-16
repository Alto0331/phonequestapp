# Makefile pour compiler server-native.c avec le NDK

NDK_HOME ?= $(ANDROID_NDK_HOME)
NDK_TOOLCHAIN := $(NDK_HOME)/toolchains/llvm/prebuilt/windows-x86_64
NDK_SYSROOT := $(NDK_HOME)/sysroot
NDK_CC := $(NDK_TOOLCHAIN)/bin/aarch64-linux-android21-clang
NDK_CFLAGS := -target aarch64-linux-android21 -fdata-sections -ffunction-sections -funwind-tables -fstack-protector-strong -no-canonical-prefixes -DANDROID -fPIC
NDK_LDFLAGS := -target aarch64-linux-android21 -Wl,--build-id=sha1 -Wl,--warn-shared-textrel -Wl,--fatal-warnings -Wl,--no-undefined-version -Wl,-z,noexecstack -Wl,-z,relro -Wl,-z,now -Wl,--gc-sections
NDK_LIBS := -llog

SRC := server-native.c
TARGET := libserver.so
OUT_DIR := libs/arm64-v8a

all: compile

compile:
	mkdir -p $(OUT_DIR)
	$(NDK_CC) $(NDK_CFLAGS) -I$(ANDK_HOME)/sysroot/usr/include -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/linux -shared -o $(OUT_DIR)/$(TARGET) $(SRC) $(NDK_LDFLAGS) $(NDK_LIBS)

clean:
	rm -rf $(OUT_DIR)
