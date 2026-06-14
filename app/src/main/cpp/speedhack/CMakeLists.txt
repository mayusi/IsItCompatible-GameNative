cmake_minimum_required(VERSION 3.22)
project(speedhack C)

set(CMAKE_C_STANDARD 11)

add_library(speedhack SHARED libspeedhack.c)

# speedhack_protocol.h lives in the same directory as libspeedhack.c
target_include_directories(speedhack PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(log-lib  log)
find_library(dl-lib   dl)

target_link_libraries(speedhack
        ${log-lib}
        ${dl-lib}
)

target_link_options(speedhack PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
)

target_compile_options(speedhack PRIVATE
        # -fvisibility=hidden: no clock_gettime/gettimeofday symbols are exported.
        # The .so no longer interposes any libc function, making it impossible
        # for loading it via LD_PRELOAD to hang the dynamic linker at game launch.
        -fvisibility=hidden
        -O2
        -Wall
        -Wextra
)

# Strip the final .so in release builds to reduce APK size
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET speedhack POST_BUILD
            COMMAND ${CMAKE_STRIP} --strip-unneeded $<TARGET_FILE:speedhack>
    )
endif()
