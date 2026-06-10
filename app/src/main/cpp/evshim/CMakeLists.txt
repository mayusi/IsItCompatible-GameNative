cmake_minimum_required(VERSION 3.22)
project(evshim C)

set(CMAKE_C_STANDARD 11)

# Prefer the local SDL2 stub header (bundles only the definitions evshim needs).
# The stub lives next to evshim.c so ${CMAKE_CURRENT_SOURCE_DIR} is always valid.
# Fall back to the tree-level third_party dir if it ever ships real SDL2 headers.
set(SDL2_STUB_DIR ${CMAKE_CURRENT_SOURCE_DIR})
set(SDL2_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/../third_party)

add_library(evshim SHARED evshim.c)

target_include_directories(evshim PRIVATE
        ${SDL2_STUB_DIR}
        ${SDL2_INCLUDE_DIR}
)

find_library(log-lib  log)
find_library(dl-lib   dl)

target_link_libraries(evshim
        ${log-lib}
        ${dl-lib}
)

target_link_options(evshim PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
)

target_compile_options(evshim PRIVATE
        -fvisibility=hidden
        -O3
        -Wall
        -Wextra
)

# Strip the final .so in release builds
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET evshim POST_BUILD
            COMMAND ${CMAKE_STRIP} --strip-unneeded $<TARGET_FILE:evshim>
    )
endif()
