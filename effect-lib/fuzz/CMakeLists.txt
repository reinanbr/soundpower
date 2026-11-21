# libFuzzer target for the command parser. Build with the host-fuzz preset, then run e.g.
#   build/host-fuzz/effect-lib/fuzz/sp_effect_fuzz -max_total_time=300
if(NOT SP_HAVE_AELI)
    message(FATAL_ERROR "SP_BUILD_FUZZERS needs the AELI glue (Clang)")
endif()
add_executable(sp_effect_fuzz fuzz_effect.cpp)
target_link_libraries(sp_effect_fuzz PRIVATE sp_effect_aeli)
target_link_options(sp_effect_fuzz PRIVATE -fsanitize=fuzzer)
sp_set_warnings(sp_effect_fuzz)

if(SP_BUILD_TESTS)
    # A short run on every test pass; long runs are done by hand.
    add_test(NAME sp_effect_fuzz_smoke COMMAND sp_effect_fuzz -runs=200000 -seed=1)
    set_tests_properties(sp_effect_fuzz_smoke PROPERTIES LABELS fuzz)
endif()
