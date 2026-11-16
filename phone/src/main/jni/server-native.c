// Serveur natif intégré pour Android
// Compilé en bibliothèque partagée (.so) pour contourner SELinux
// Port: 27183

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <pthread.h>
#include <android/log.h>

#define LOG_TAG "ServerNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define PORT 27183
#define BUFFER_SIZE 65536

// Déclarations forward
static void* accept_thread(void* arg);
static void* handle_client(void* arg);

// Références globales pour le callback
static JavaVM *g_jvm = NULL;
static jobject g_server_manager_obj = NULL;
static jmethodID g_notify_method = NULL;
static int g_server_socket = -1;
static volatile int g_running = 1;

// Thread pour gérer un client (envoie des frames simulées)
static void* handle_client(void* arg) {
    int client_socket = *(int*)arg;
    free(arg);
    
    LOGI("Nouvelle connexion client");
    
    // Enviyer un message de bienvenue
    char welcome_msg[] = "SCRCPY_SERVER_TYPE_VIDEO\n";
    if (write(client_socket, welcome_msg, strlen(welcome_msg)) < 0) {
        LOGE("Erreur écriture message bienvenue");
        close(client_socket);
        return NULL;
    }
    
    LOGI("Message bienvenue envoyé");
    
    // Envoyer des frames simulées en continu
    char frame_data[BUFFER_SIZE];
    
    // Simuler un flux H.265 avec des frames NAL
    // Structure simple : [0x00 0x00 0x00 0x01][type][data...]
    int frame_counter = 0;
    
    while (g_running) {
        // Créer une frame simulée
        // Header NAL : 0x00 0x00 0x00 0x01
        frame_data[0] = 0x00;
        frame_data[1] = 0x00;
        frame_data[2] = 0x00;
        frame_data[3] = 0x01;
        
        // Type NAL (0x67 = SPS, 0x68 = PPS, 0x65 = IDR, 0x41 = P-frame)
        // Alternons entre IDR et P-frames
        if (frame_counter % 30 == 0) {
            frame_data[4] = 0x67; // SPS
        } else if (frame_counter % 30 == 1) {
            frame_data[4] = 0x68; // PPS  
        } else if (frame_counter % 30 == 2) {
            frame_data[4] = 0x65; // IDR frame
        } else {
            frame_data[4] = 0x41; // P-frame
        }
        
        // Remplir avec des données simulées (valeurs qui changent)
        for (int i = 5; i < 100; i++) {
            frame_data[i] = (frame_counter + i) % 256;
        }
        
        // Envoyer la frame (taille variable pour simuler)
        int frame_size = 50 + (frame_counter % 100);
        if (frame_size > BUFFER_SIZE) frame_size = BUFFER_SIZE;
        
        ssize_t sent = write(client_socket, frame_data, frame_size);
        if (sent <= 0) {
            LOGE("Erreur écriture frame, sent=%zd, errno=%d", sent, errno);
            break;
        }
        
        frame_counter++;
        
        // Attendre ~33ms pour simuler 30 FPS
        usleep(33333);
    }
    
    LOGI("Fermeture connexion client");
    close(client_socket);
    return NULL;
}

// Fonction pour notifier Kotlin que le serveur a démarré
static void notify_server_started() {
    if (!g_jvm || !g_server_manager_obj || !g_notify_method) {
        LOGE("Callback not initialized: g_jvm=%p, obj=%p, method=%p", 
             (void*)g_jvm, (void*)g_server_manager_obj, (void*)g_notify_method);
        return;
    }
    
    JNIEnv *env;
    int attached = 0;
    
    // Attacher le thread au JVM
    jint res = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (res != JNI_OK) {
        res = (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
        if (res != JNI_OK) {
            LOGE("Failed to attach thread: %d", res);
            return;
        }
        attached = 1;
    }
    
    LOGI("Appel notifyServerStarted sur l'instance");
    
    // Appeler la méthode notifyServerStarted sur l'objet ServerManager
    (*env)->CallVoidMethod(env, g_server_manager_obj, g_notify_method);
    
    if (attached) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

// Fonction JNI pour démarrer le serveur
JNIEXPORT jboolean JNICALL
Java_com_example_phone_ServerManager_startServerNative(JNIEnv *env, jobject obj) {
    LOGI("=== Démarrage du serveur scrcpy (natif) ===");
    
    // Stocker les références pour le callback
    (*env)->GetJavaVM(env, &g_jvm);
    
    if (!g_server_manager_obj) {
        g_server_manager_obj = (*env)->NewGlobalRef(env, obj);
    }
    
    jclass clazz = (*env)->GetObjectClass(env, obj);
    g_notify_method = (*env)->GetMethodID(env, clazz, "notifyServerStarted", "()V");
    
    if (!g_notify_method) {
        LOGE("Failed to get notifyServerStarted method");
        return JNI_FALSE;
    }
    
    LOGI("Méthode notifyServerStarted obtenue: %p", (void*)g_notify_method);
    
    // Créer le socket
    g_server_socket = socket(AF_INET, SOCK_STREAM, 0);
    if (g_server_socket < 0) {
        LOGE("socket() échoué, errno=%d: %s", errno, strerror(errno));
        return JNI_FALSE;
    }
    
    // Options
    int opt = 1;
    if (setsockopt(g_server_socket, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt))) {
        LOGE("setsockopt() échoué");
        close(g_server_socket);
        g_server_socket = -1;
        return JNI_FALSE;
    }
    
    struct sockaddr_in address;
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = INADDR_ANY;
    address.sin_port = htons(PORT);
    
    // Bind
    if (bind(g_server_socket, (struct sockaddr *)&address, sizeof(address)) < 0) {
        LOGE("bind() échoué, errno=%d: %s", errno, strerror(errno));
        close(g_server_socket);
        g_server_socket = -1;
        return JNI_FALSE;
    }
    
    // Listen
    if (listen(g_server_socket, 5) < 0) {
        LOGE("listen() échoué");
        close(g_server_socket);
        g_server_socket = -1;
        return JNI_FALSE;
    }
    
    LOGI("Serveur démarré sur le port %d", PORT);
    
    // Notifier Kotlin que le serveur a démarré
    notify_server_started();
    
    // Démarrer le thread d'accept
    pthread_t thread;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    
    if (pthread_create(&thread, &attr, accept_thread, NULL) != 0) {
        LOGE("Échec création thread accept");
        close(g_server_socket);
        g_server_socket = -1;
        return JNI_FALSE;
    }
    
    pthread_attr_destroy(&attr);
    
    LOGI("Serveur complètement initialisé");
    return JNI_TRUE;
}

// Thread pour accepter les connexions
static void* accept_thread(void* arg) {
    (void)arg;
    
    LOGI("Thread accept démarré");
    
    while (g_running) {
        struct sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        int *client_socket = malloc(sizeof(int));
        
        *client_socket = accept(g_server_socket, (struct sockaddr *)&client_addr, &client_len);
        
        if (*client_socket < 0) {
            if (!g_running) break;
            LOGE("accept() échoué, errno=%d", errno);
            usleep(100000); // 100ms
            free(client_socket);
            continue;
        }
        
        LOGI("Client connecté depuis %s:%d", 
             inet_ntoa(client_addr.sin_addr), ntohs(client_addr.sin_port));
        
        // Démarrer un thread pour gérer ce client
        pthread_t client_thread;
        pthread_attr_t client_attr;
        pthread_attr_init(&client_attr);
        pthread_attr_setdetachstate(&client_attr, PTHREAD_CREATE_DETACHED);
        
        if (pthread_create(&client_thread, &client_attr, handle_client, client_socket) != 0) {
            LOGE("Échec création thread client");
            close(*client_socket);
            free(client_socket);
        }
        
        pthread_attr_destroy(&client_attr);
    }
    
    LOGI("Thread accept terminé");
    return NULL;
}

// Fonction JNI pour arrêter le serveur
JNIEXPORT void JNICALL
Java_com_example_phone_ServerManager_stopServerNative(JNIEnv *env, jobject obj) {
    LOGI("=== Arrêt du serveur scrcpy ===");
    g_running = 0;
    
    if (g_server_socket >= 0) {
        close(g_server_socket);
        g_server_socket = -1;
    }
    
    if (g_server_manager_obj) {
        (*env)->DeleteGlobalRef(env, g_server_manager_obj);
        g_server_manager_obj = NULL;
    }
    
    g_notify_method = NULL;
    LOGI("Serveur arrêté");
}
