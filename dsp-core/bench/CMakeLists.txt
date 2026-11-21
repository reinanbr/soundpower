add_library(sp_bench_lib STATIC bench.cpp)
target_include_directories(sp_bench_lib PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(sp_bench_lib PUBLIC sp_dsp_core)
sp_set_warnings(sp_bench_lib)

add_executable(sp-bench main.cpp)
target_link_libraries(sp-bench PRIVATE sp_bench_lib)
sp_set_warnings(sp-bench)

if(SP_BUILD_TESTS)
    # Per-block CPU benchmark. It always passes; it exists so CI logs record the numbers.
    add_test(NAME sp_bench_quick COMMAND sp-bench --quick)
    set_tests_properties(sp_bench_quick PROPERTIES LABELS bench)
endif()
