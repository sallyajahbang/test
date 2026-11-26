cmake_minimum_required(VERSION 3.22.1)
project(cpplauncher CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(cpplauncher SHARED launcher_jni.cpp)

find_library(log-lib log)
target_link_libraries(cpplauncher ${log-lib} dl)
