cmake_minimum_required(VERSION 3.22.1)
project("retro-tts")

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -flto -ffast-math -funroll-loops -fomit-frame-pointer")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -flto -ffast-math -funroll-loops -fomit-frame-pointer")

set(DECTALKMINI_NO_CHARSET ON CACHE BOOL "Disable charset conversion" FORCE)
set(DECTALKMINI_NO_FILESYSTEM ON CACHE BOOL "Disable filesystem" FORCE)
set(DECTALKMINI_EXAMPLES OFF CACHE BOOL "Build examples" FORCE)

# DECtalkMini's CMakeLists.txt requires CMake >= 3.25, but the NDK toolchain ships 3.22.1.
# Relax it to match our toolchain so a fresh submodule checkout builds without manual edits.
set(DECTALK_CMAKE ${CMAKE_CURRENT_SOURCE_DIR}/dectalk_repo/CMakeLists.txt)
if(EXISTS ${DECTALK_CMAKE})
    file(READ ${DECTALK_CMAKE} _dectalk_cmake)
    string(REGEX REPLACE "cmake_minimum_required\\(VERSION [0-9.]+\\)"
           "cmake_minimum_required(VERSION 3.22.1)" _dectalk_cmake_patched ${_dectalk_cmake})
    if(NOT _dectalk_cmake_patched STREQUAL _dectalk_cmake)
        file(WRITE ${DECTALK_CMAKE} ${_dectalk_cmake_patched})
    endif()
else()
    message(FATAL_ERROR
        "dectalk_repo submodule not found. Run: git submodule update --init --recursive")
endif()

add_subdirectory(dectalk_repo)

set(SAM_SOURCES
    sam_repo/src/reciter.c
    sam_repo/src/sam.c
    sam_repo/src/render.c
    sam_repo/src/debug.c
)

file(GLOB OPENEVV_SRC_SOURCES "openevv_repo/src/*.c")
list(FILTER OPENEVV_SRC_SOURCES EXCLUDE REGEX ".*port_win32\\.c$")
file(GLOB OPENEVV_LANG_SOURCES "openevv_repo/lang/enus/*.c")
set(OPENEVV_SOURCES ${OPENEVV_SRC_SOURCES} ${OPENEVV_LANG_SOURCES})

add_library(retro-tts SHARED native-lib.cpp ${SAM_SOURCES} ${OPENEVV_SOURCES})

target_compile_definitions(retro-tts PRIVATE EVV_ARENA=1)

add_library(espeak SHARED IMPORTED)
set_target_properties(espeak PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libttsespeak.so)

target_include_directories(retro-tts PRIVATE 
    sam_repo/src 
    dectalk_repo/include 
    ../../../../../espeak-ng/src/include
    openevv_repo/src
    openevv_repo/lang/enus
)

find_library(log-lib log)

target_link_libraries(retro-tts dtc espeak ${log-lib})
