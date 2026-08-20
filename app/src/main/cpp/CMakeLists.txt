cmake_minimum_required(VERSION 3.22.1)
project("retro-tts")

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -flto -ffast-math -funroll-loops -fomit-frame-pointer")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -flto -ffast-math -funroll-loops -fomit-frame-pointer")

set(DECTALKMINI_NO_CHARSET ON CACHE BOOL "Disable charset conversion" FORCE)
set(DECTALKMINI_NO_FILESYSTEM ON CACHE BOOL "Disable filesystem" FORCE)
set(DECTALKMINI_EXAMPLES OFF CACHE BOOL "Build examples" FORCE)
add_subdirectory(dectalk_repo)

set(SAM_SOURCES
    sam_repo/src/reciter.c
    sam_repo/src/sam.c
    sam_repo/src/render.c
    sam_repo/src/debug.c
)

add_library(retro-tts SHARED native-lib.cpp ${SAM_SOURCES})

add_library(espeak SHARED IMPORTED)
set_target_properties(espeak PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libttsespeak.so)

target_include_directories(retro-tts PRIVATE sam_repo/src dectalk_repo/include ../../../../../espeak-ng/src/include)

find_library(log-lib log)

target_link_libraries(retro-tts dtc espeak ${log-lib})
