cmake_minimum_required(VERSION 3.10)
project(thinkos_lua C)
file(GLOB LUA_SOURCES CONFIGURE_DEPENDS lua/*.c)
list(FILTER LUA_SOURCES EXCLUDE REGEX "/(lua|luac)\\.c$")
add_library(tos SHARED tos_bridge.c json/yyjson.c ${LUA_SOURCES})
target_include_directories(tos PRIVATE lua)
target_compile_options(tos PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(tos m)

target_compile_definitions(tos PRIVATE YYJSON_DISABLE_FILE=1 YYJSON_DISABLE_UTILS=1 YYJSON_READER_DEPTH_LIMIT=25 YYJSON_WRITER_DEPTH_LIMIT=25)

# FairPlay key decryption for AirPlay screen mirroring (GPLv3, see playfair/ORIGIN.md). The reverse-engineered C warns a lot and is not ours to rewrite.
add_library(thinkos_playfair SHARED
    fairplay_jni.c
    playfair/playfair.c
    playfair/omg_hax.c
    playfair/hand_garble.c
    playfair/modified_md5.c
    playfair/sap_hash.c)
target_include_directories(thinkos_playfair PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/playfair)
target_compile_options(thinkos_playfair PRIVATE -O2 -w)
