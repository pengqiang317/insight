cmake_minimum_required(VERSION 3.22.1)
project(insight-native C)

add_library(insight-native SHARED insight_crash.c)
target_compile_options(insight-native PRIVATE -Wall -Wextra -Wno-unused-parameter -O2 -fno-omit-frame-pointer)
target_link_libraries(insight-native log)
