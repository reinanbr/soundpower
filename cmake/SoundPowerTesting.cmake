# GoogleTest and test registration, shared by every test directory.

include(FetchContent)

if(POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)  # extracted timestamps = extraction time
endif()

FetchContent_Declare(googletest
    URL https://github.com/google/googletest/releases/download/v1.17.0/googletest-1.17.0.tar.gz
    URL_HASH SHA256=65fab701d9829d38cb77c14acdc431d2108bfdbf8979e40eb8ae567edf10b27c)
set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
set(BUILD_GMOCK OFF CACHE BOOL "" FORCE)
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)
include(GoogleTest)

# The zero-allocation tests replace the global operator new. Clang's ThreadSanitizer runtime is
# linked statically and defines operator new itself, so those tests can't link (and TSan builds
# measure nothing useful about allocation anyway).
if(SP_SANITIZE MATCHES "thread")
    set(SP_ALLOCATION_TESTS OFF)
else()
    set(SP_ALLOCATION_TESTS ON)
endif()

# sp_add_gtest(<target> [ENVIRONMENT <var=value>...]): registers a GoogleTest executable.
#
# Native builds register every test case separately. Cross builds (Android) register the whole
# executable as one test: CTest runs it through CMAKE_CROSSCOMPILING_EMULATOR, which for the
# Android presets is scripts/adb-exec.sh, so `ctest` runs the suite on a connected device.
function(sp_add_gtest target)
    cmake_parse_arguments(PARSE_ARGV 1 arg "" "" "ENVIRONMENT")
    if(CMAKE_CROSSCOMPILING)
        add_test(NAME ${target} COMMAND ${target})
        if(arg_ENVIRONMENT)
            set_tests_properties(${target} PROPERTIES ENVIRONMENT "${arg_ENVIRONMENT}")
        endif()
    elseif(arg_ENVIRONMENT)
        gtest_discover_tests(${target} DISCOVERY_TIMEOUT 60
            PROPERTIES ENVIRONMENT "${arg_ENVIRONMENT}")
    else()
        gtest_discover_tests(${target} DISCOVERY_TIMEOUT 60)
    endif()
endfunction()
