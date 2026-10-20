cmake_minimum_required(VERSION 3.18.1)
project("native-lib")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(native-lib SHARED src/main/cpp/native-lib.cpp)

add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/libnode/bin/${ANDROID_ABI}/libnode.so)

include_directories(${CMAKE_SOURCE_DIR}/libnode/include/node/)

find_library(log-lib log)
target_link_libraries(native-lib node ${log-lib})
