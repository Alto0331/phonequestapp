#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <errno.h>
#include <android/log.h>

#define LOG_TAG "CoreNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int socket_fd = -1;
static int width = 0;
static int height = 0;

JNIEXPORT jboolean JNICALL
Java_com_example_quest_Core_nativeConnect(JNIEnv *env, jobject obj, jstring ip, jint port) {
    const char *ip_str = (*env)->GetStringUTFChars(env, ip, NULL);
    
    LOGI("nativeConnect: %s:%d", ip_str, port);
    
    socket_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (socket_fd < 0) {
        LOGE("Erreur socket: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, ip, ip_str);
        return JNI_FALSE;
    }

    struct sockaddr_in server_addr;
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    server_addr.sin_port = htons(port);
    inet_pton(AF_INET, ip_str, &server_addr.sin_addr);

    (*env)->ReleaseStringUTFChars(env, ip, ip_str);

    if (connect(socket_fd, (struct sockaddr*)&server_addr, sizeof(server_addr)) < 0) {
        LOGE("Erreur connect: %s (errno=%d)", strerror(errno), errno);
        close(socket_fd);
        socket_fd = -1;
        return JNI_FALSE;
    }

    LOGI("Connecté à %s:%d", ip_str, port);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_quest_Core_nativeHandshake(JNIEnv *env, jobject obj) {
    LOGI("nativeHandshake: début");
    
    if (socket_fd < 0) {
        LOGE("Socket non connecté");
        return JNI_FALSE;
    }

    char buf[512];
    ssize_t n;

    // Envoyer la requête de handshake scrcpy
    char handshake[19] = {0};
    handshake[0] = 0x01;
    handshake[1] = 0x00;
    handshake[2] = 0x00;
    handshake[3] = 0x00;

    // Écrire la taille du payload
    uint32_t size = htonl(15);
    memcpy(handshake + 4, &size, 4);

    if (write(socket_fd, handshake, 19) < 0) {
        LOGE("Erreur write handshake: %s", strerror(errno));
        return JNI_FALSE;
    }

    LOGI("Handshake envoyé");

    // Lire la réponse du serveur
    n = read(socket_fd, buf, sizeof(buf));
    if (n <= 0) {
        LOGE("Erreur read: %s", strerror(errno));
        return JNI_FALSE;
    }

    LOGI("Handshake reçu: %zd bytes", n);

    // Lire width et height depuis les 4 premiers bytes (little-endian)
    if (n >= 4) {
        width = (buf[0] & 0xFF) | ((buf[1] & 0xFF) << 8);
        height = (buf[2] & 0xFF) | ((buf[3] & 0xFF) << 8);
        LOGI("Résolution: %d x %d", width, height);
    }

    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_example_quest_Core_nativeGetWidth(JNIEnv *env, jobject obj) {
    LOGI("nativeGetWidth: %d", width);
    return (jint)width;
}

JNIEXPORT jint JNICALL
Java_com_example_quest_Core_nativeGetHeight(JNIEnv *env, jobject obj) {
    LOGI("nativeGetHeight: %d", height);
    return (jint)height;
}

JNIEXPORT void JNICALL
Java_com_example_quest_Core_nativeDisconnect(JNIEnv *env, jobject obj) {
    LOGI("nativeDisconnect");
    if (socket_fd >= 0) {
        close(socket_fd);
        socket_fd = -1;
    }
    width = 0;
    height = 0;
}

// Nouvelle fonction pour lire des données depuis le socket
JNIEXPORT jint JNICALL
Java_com_example_quest_Core_nativeReadFrame(JNIEnv *env, jobject obj, jbyteArray buffer, jint offset, jint length) {
    if (socket_fd < 0) {
        LOGE("Socket non connecté");
        return -1;
    }
    
    if (length <= 0) {
        LOGE("Longueur invalide: %d", length);
        return -1;
    }
    
    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    
    ssize_t n = read(socket_fd, buf + offset, length);
    
    (*env)->ReleaseByteArrayElements(env, buffer, buf, JNI_ABORT);
    
    if (n < 0) {
        LOGE("Erreur read frame: %s", strerror(errno));
        return -1;
    }
    
    if (n == 0) {
        LOGI("Connection closed by peer");
        return 0;
    }
    
    LOGD("Lecture de %zd bytes", n);
    return (jint)n;
}
