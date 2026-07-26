cmake_minimum_required(VERSION 3.22.1)
project(mclauncher_engine LANGUAGES C CXX)

add_library(mclauncher SHARED native_engine.cpp)

target_compile_features(mclauncher PRIVATE cxx_std_20)
target_compile_options(mclauncher PRIVATE -Wall -Wextra -Werror=return-type)

target_link_libraries(
    mclauncher
    android
    log
    dl
)
