# CMake configuration for GBT Rust library
cmake_minimum_required(VERSION 3.18.1)

project("gbt")

# Add library
add_library(gbt SHARED IMPORTED)

# Set the imported library properties
set_target_properties(gbt PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/gbt/target/${ANDROID_ABI}/release/libgbt.so
)

# Create empty source file so CMake doesn't complain
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/empty.c "// empty file for CMake")
add_library(gbt-wrapper SHARED ${CMAKE_CURRENT_BINARY_DIR}/empty.c)
target_link_libraries(gbt-wrapper gbt)