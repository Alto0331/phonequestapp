// Quest Mirror - Native JNI implementation
#include <jni.h>
#include <android/log.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>

#define LOG_TAG "CoreNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static int g_socket = -1;
static int g_width = 0;
static int g_height = 0;

JNIEXPORT jboolean JNICALL
Java_com_example_quest_Core_nativeConnect(JNIEnv *env, jobject thiz, jstring ip, jint port) {
    LOGI("nativeConnect to port %d", port);
    
    const char *ipStr = (*env)->GetStringUTFChars(env, ip, NULL);
    
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    
    if (inet_pton(AF_INET, ipStr, &addr.sin_addr) <= 0) {
        LOGE("Invalid IP");
        (*env)->ReleaseStringUTFChars(env, ip, ipStr);
        return JNI_FALSE;
    }
    
    if (g_socket >= 0) close(g_socket);
    
    g_socket = socket(AF_INET, SOCK_STREAM, 0);
    if (g_socket < 0) {
        LOGE("Socket creation failed");
        (*env)->ReleaseStringUTFChars(env, ip, ipStr);
        return JNI_FALSE;
    }
    
    struct timeval tv;
    tv.tv_sec = 5; tv.tv_usec = 0;
    setsockopt(g_socket, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tv, sizeof(tv));
    setsockopt(g_socket, SOL_SOCKET, SO_SNDTIMEO, (const char*)&tv, sizeof(tv));
    
    if (connect(g_socket, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("Connection failed");
        close(g_socket);
        g_socket = -1;
        (*env)->ReleaseStringUTFChars(env, ip, ipStr);
        return JNI_FALSE;
    }
    
    (*env)->ReleaseStringUTFChars(env, ip, ipStr);
    LOGI("Connected to server");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_quest_Core_nativeHandshake(JNIEnv *env, jobject thiz) {
    LOGI("nativeHandshake");
    if (g_socket < 0) return JNI_FALSE;
    
    // Lire la version serveur (4 bytes)
    uint32_t version;
    if (recv(g_socket, &version, 4, 0) != 4) {
        LOGE("Recv version failed");
        return JNI_FALSE;
    }
    LOGI("Server version: %u", ntohl(version));
    
    // Lire le nom du device (32 bytes)
    char deviceName[33];
    if (recv(g_socket, deviceName, 32, 0) != 32) {
        LOGE("Recv device name failed");
        return JNI_FALSE;
    }
    deviceName[32] = '\0';
    LOGI("Device name: %s", deviceName);
    
    // Lire les infos device (largeur, hauteur, dpi, flags)
    uint32_t deviceInfo[4];
    if (recv(g_socket, deviceInfo, 16, 0) != 16) {
        LOGE("Recv device info failed");
        return JNI_FALSE;
    }
    g_width = ntohl(deviceInfo[0]);
    g_height = ntohl(deviceInfo[1]);
    LOGI("Device: %dx%d", g_width, g_height);
    
    // Envoyer la réponse client (8 bytes: version + flags)
    uint32_t clientInfo[] = {htonl(2), 0};
    if (send(g_socket, clientInfo, 8, 0) != 8) {
        LOGE("Send client info failed");
        return JNI_FALSE;
    }
    
    // Lire les paramètres vidéo (maxSize, bitrate, fps)
    uint32_t params[3];
    if (recv(g_socket, params, 12, 0) != 12) {
        LOGE("Recv params failed");
        return JNI_FALSE;
    }
    LOGI("Params: maxSize=%u, bitrate=%u, fps=%u", ntohl(params[0]), ntohl(params[1]), ntohl(params[2]));
    
    // Lire le format vidéo
    uint8_t videoFormat;
    if (recv(g_socket, &videoFormat, 1, 0) != 1) {
        LOGE("Recv video format failed");
        return JNI_FALSE;
    }
    LOGI("Video format: 0x%02x", videoFormat);
    
    // Lire latency
    uint8_t latency;
    if (recv(g_socket, &latency, 1, 0) != 1) {
        LOGE("Recv latency failed");
        return JNI_FALSE;
    }
    
    // Lire flags
    uint32_t flags;
    if (recv(g_socket, &flags, 4, 0) != 4) {
        LOGE("Recv flags failed");
        return JNI_FALSE;
    }
    
    // Envoyer les params vidéo au serveur (16 bytes)
    uint32_t videoParams[4] = {0};
    if (send(g_socket, videoParams, 16, 0) != 16) {
        LOGE("Send video params failed");
        return JNI_FALSE;
    }
    
    // Lire la configuration codec (8 bytes)
    uint8_t codecConfig[8];
    if (recv(g_socket, codecConfig, 8, 0) != 8) {
        LOGE("Recv codec config failed");
        return JNI_FALSE;
    }
    
    LOGI("Handshake OK: %dx%d", g_width, g_height);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_example_quest_Core_nativeGetWidth(JNIEnv *env, jobject thiz) {
    return g_width;
}

JNIEXPORT jint JNICALL
Java_com_example_quest_Core_nativeGetHeight(JNIEnv *env, jobject thiz) {
    return g_height;
}

JNIEXPORT void JNICALL
Java_com_example_quest_Core_nativeDisconnect(JNIEnv *env, jobject thiz) {
    LOGI("nativeDisconnect");
    if (g_socket >= 0) close(g_socket);
    g_socket = -1;
}

JNIEXPORT jint JNICALL
Java_com_example_quest_Core_nativeReadFrame(JNIEnv *env, jobject thiz, jbyteArray buffer, jint offset, jint length) {
    if (g_socket < 0) return -1;
    
    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    jsize bytesRead = recv(g_socket, buf + offset, length, 0);
    (*env)->ReleaseByteArrayElements(env, buffer, buf, 0);
    
    if (bytesRead < 0) {
        LOGE("Recv failed, errno=%d", errno);
        return -1;
    }
    if (bytesRead == 0) {
        LOGI("Connection closed by server");
        return 0;
    }
    
    return bytesRead;
}
