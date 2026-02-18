cmake_minimum_required(VERSION 3.22.1)

project("gbt")

# Add Rust library
add_library(gbt SHARED IMPORTED)

# Set properties based on target architecture
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set_target_properties(gbt PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/gbt/target/aarch64-linux-android/release/libgbt.so)
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set_target_properties(gbt PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/gbt/target/armv7-linux-androideabi/release/libgbt.so)
elseif(${ANDROID_ABI} STREQUAL "x86_64")
    set_target_properties(gbt PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/gbt/target/x86_64-linux-android/release/libgbt.so)
elseif(${ANDROID_ABI} STREQUAL "x86")
    set_target_properties(gbt PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/gbt/target/i686-linux-android/release/libgbt.so)
endif()

# Custom target to build Rust library
add_custom_target(build_rust_gbt ALL
    COMMAND cargo build --release --target aarch64-linux-android
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/gbt
    COMMENT "Building Rust GBT library")

# Make sure the Rust library is built before Android uses it
add_dependencies(gbt build_rust_gbt)