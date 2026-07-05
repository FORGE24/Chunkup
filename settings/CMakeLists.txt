cmake_minimum_required(VERSION 3.16)

project(chunkup-settings VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)
set(CMAKE_AUTOUIC ON)

if(DEFINED ENV{QT_ROOT})
    set(QT_ROOT "$ENV{QT_ROOT}" CACHE PATH "Qt installation root")
else()
    set(QT_ROOT "D:/Qt/6.11.1/mingw_64" CACHE PATH "Qt installation root")
endif()

list(APPEND CMAKE_PREFIX_PATH "${QT_ROOT}")
find_package(Qt6 REQUIRED COMPONENTS Widgets)

if(DEFINED ENV{JAVA_HOME})
    file(TO_CMAKE_PATH "$ENV{JAVA_HOME}" JAVA_HOME_DIR)
else()
    message(FATAL_ERROR "JAVA_HOME is required to build chunkup_settings JNI bindings")
endif()

set(JNI_INCLUDE_DIRS
    "${JAVA_HOME_DIR}/include"
    "${JAVA_HOME_DIR}/include/win32"
)
if(NOT EXISTS "${JAVA_HOME_DIR}/include/jni.h")
    message(FATAL_ERROR "jni.h not found under JAVA_HOME=${JAVA_HOME_DIR}")
endif()

add_library(chunkup_settings SHARED
    src/SettingsHost.cpp
    src/chunkup_settings_jni.cpp
    src/MainWindow.cpp
    src/MainWindow.h
    src/ConfigManager.cpp
    src/ConfigManager.h
    include/chunkup_settings_api.h
)

target_compile_definitions(chunkup_settings PRIVATE CHUNKUP_SETTINGS_EXPORT_BUILD)
target_include_directories(chunkup_settings PRIVATE include ${JNI_INCLUDE_DIRS})
target_link_libraries(chunkup_settings PRIVATE Qt6::Widgets)

if(WIN32)
    set_target_properties(chunkup_settings PROPERTIES PREFIX "")
endif()

install(TARGETS chunkup_settings RUNTIME DESTINATION bin LIBRARY DESTINATION bin)
