cmake_minimum_required(VERSION 3.22.1)

project(ttsmp3 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)

include(FetchContent)

FetchContent_Declare(
    lame_source
    URL https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz
)

FetchContent_GetProperties(lame_source)

if(NOT lame_source_POPULATED)
    FetchContent_Populate(lame_source)
endif()

file(GLOB LAME_SOURCES CONFIGURE_DEPENDS
    "${lame_source_SOURCE_DIR}/libmp3lame/*.c"
)

add_library(mp3lame SHARED ${LAME_SOURCES})

target_include_directories(mp3lame
    PUBLIC
        "${lame_source_SOURCE_DIR}/include"
    PRIVATE
        "${lame_source_SOURCE_DIR}"
        "${lame_source_SOURCE_DIR}/libmp3lame"
)

target_compile_definitions(mp3lame PRIVATE
    STDC_HEADERS=1
    HAVE_STDINT_H=1
    HAVE_INTTYPES_H=1
    HAVE_STDLIB_H=1
    HAVE_STRING_H=1
    HAVE_LIMITS_H=1
    HAVE_UNISTD_H=1
    HAVE_ERRNO_H=1
    HAVE_FCNTL_H=1
)

target_link_libraries(mp3lame PRIVATE m)

add_library(ttsmp3 SHARED mp3_encoder.cpp)

target_link_libraries(ttsmp3 PRIVATE mp3lame)
