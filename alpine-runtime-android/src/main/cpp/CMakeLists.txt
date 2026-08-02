cmake_minimum_required(VERSION 3.22.1)
project(alpine_runtime_pty C)

add_library(alpine-runtime-pty SHARED pty_bridge.c)

set_target_properties(
    alpine-runtime-pty
    PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
)

target_link_options(
    alpine-runtime-pty
    PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
