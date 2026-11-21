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
sp_add_gtest(sp_dsp_core_tests)

# Separate executable: it replaces the global allocation functions.
if(SP_ALLOCATION_TESTS)
    add_executable(sp_dsp_core_rt_alloc_test test_rt_alloc.cpp)
    target_link_libraries(sp_dsp_core_rt_alloc_test PRIVATE sp_dsp_core GTest::gtest_main)
    sp_set_warnings(sp_dsp_core_rt_alloc_test)
    sp_add_gtest(sp_dsp_core_rt_alloc_test)
endif()
