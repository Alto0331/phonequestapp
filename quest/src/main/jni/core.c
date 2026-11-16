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
    LOGI("Connected");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_quest_Core_nativeHandshake(JNIEnv *env, jobject thiz) {
    LOGI("nativeHandshake");
    if (g_socket < 0) return JNI_FALSE;
    
    // Envoyer la version serveur (4 bytes)
    uint32_t version = htonl(2);
    if (send(g_socket, &version, 4, 0) != 4) {
        LOGE("Send version failed");
        return JNI_FALSE;
    }
    
    // Envoyer les infos device (largeur, hauteur, dpi, flags)
    uint32_t deviceInfo[] = {htonl(g_width), htonl(g_height), 0, 0};
    if (send(g_socket, deviceInfo, 16, 0) != 16) {
        LOGE("Send device info failed");
        return JNI_FALSE;
    }
    
    // Lire la réponse client (8 bytes: version + flags)
    uint32_t clientInfo[2];
    if (recv(g_socket, clientInfo, 8, 0) != 8) {
        LOGE("Recv client info failed");
        return JNI_FALSE;
    }
    
    // Envoyer les paramètres (maxSize, bitrate, fps)
    uint32_t params[] = {0, htonl(8000000), htonl(30)};
    if (send(g_socket, params, 12, 0) != 12) {
        LOGE("Send params failed");
        return JNI_FALSE;
    }
    
    // Envoyer le format vidéo (H.265)
    uint8_t videoFormat = 0x01;
    if (send(g_socket, &videoFormat, 1, 0) != 1) return JNI_FALSE;
    
    // Envoyer latency
    uint8_t latency = 0;
    if (send(g_socket, &latency, 1, 0) != 1) return JNI_FALSE;
    
    // Envoyer flags
    uint32_t flags = 0;
    if (send(g_socket, &flags, 4, 0) != 4) return JNI_FALSE;
    
    // Lire les params vidéo du client (16 bytes)
    uint32_t videoParams[4];
    if (recv(g_socket, videoParams, 16, 0) != 16) {
        LOGE("Recv video params failed");
        return JNI_FALSE;
    }
    
    // Envoyer la configuration codec (8 bytes: header)
    uint8_t codecConfig[8] = {0};
    if (send(g_socket, codecConfig, 8, 0) != 8) {
        LOGE("Send codec config failed");
        return JNI_FALSE;
    }
    
    LOGI("Handshake OK");
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
