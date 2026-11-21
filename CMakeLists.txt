cmake_minimum_required(VERSION 3.22)

file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/VERSION" SP_VERSION LIMIT_COUNT 1)
project(soundpower VERSION ${SP_VERSION} LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

if(ANDROID)
    set(_sp_host_default OFF)
else()
    set(_sp_host_default ON)
endif()

option(SP_BUILD_TESTS "Build the dsp-core test suite" ${_sp_host_default})
option(SP_BUILD_TOOLS "Build sp-cli and sp-bench" ${_sp_host_default})
option(SP_WERROR "Treat compiler warnings as errors" OFF)
set(SP_SANITIZE "" CACHE STRING "Comma-separated sanitizers, e.g. 'address,undefined' or 'thread'")

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
include(SoundPowerCompilerOptions)

if(SP_SANITIZE)
    add_compile_options(-fsanitize=${SP_SANITIZE} -fno-omit-frame-pointer -fno-sanitize-recover=all)
    add_link_options(-fsanitize=${SP_SANITIZE})
endif()

if(SP_BUILD_TESTS)
    enable_testing()
    include(SoundPowerTesting)
endif()

add_subdirectory(protocol)
add_subdirectory(third_party)
add_subdirectory(dsp-core)
