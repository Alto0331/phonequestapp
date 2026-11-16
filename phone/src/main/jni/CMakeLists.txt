cmake_minimum_required(VERSION 3.18.1)

# Configurer la compilation pour Android
project(server)

# Ajouter la bibliothèque
add_library(server SHARED server-native.c)

# Inclure les headers JNI
find_package(JNI REQUIRED)

# Lier avec les bibliothèques nécessaires
target_link_libraries(server 
    ${JNI_LIBRARIES}
    log
)

# Configurer l'export du nom de la bibliothèque
target_include_directories(server PRIVATE ${JNI_INCLUDE_DIRECTORIES})
