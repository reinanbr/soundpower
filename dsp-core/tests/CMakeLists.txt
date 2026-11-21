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

add_executable(sp_dsp_core_tests
    test_concurrency.cpp
    test_format_and_params.cpp
    test_limiter.cpp
    test_parametric_eq.cpp
    test_processor.cpp
    test_rbj_response.cpp
    test_stability.cpp)
target_link_libraries(sp_dsp_core_tests PRIVATE sp_dsp_core GTest::gtest_main)
sp_set_warnings(sp_dsp_core_tests)
gtest_discover_tests(sp_dsp_core_tests DISCOVERY_TIMEOUT 60)
