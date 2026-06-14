cmake_minimum_required(VERSION 3.22)
project(trainer C)

set(CMAKE_C_STANDARD 11)

add_library(trainer SHARED libtrainer.c)

# trainer_protocol.h lives in the same directory as libtrainer.c
target_include_directories(trainer PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(log-lib  log)
find_library(dl-lib   dl)

target_link_libraries(trainer
        ${log-lib}
        ${dl-lib}
)

target_link_options(trainer PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
)

target_compile_options(trainer PRIVATE
        -fvisibility=hidden
        -O2
        -Wall
        -Wextra
)

# Strip the final .so in release builds
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET trainer POST_BUILD
            COMMAND ${CMAKE_STRIP} --strip-unneeded $<TARGET_FILE:trainer>
    )
endif()
