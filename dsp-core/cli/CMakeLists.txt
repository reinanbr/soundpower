add_executable(sp-cli main.cpp)
target_link_libraries(sp-cli PRIVATE sp_dsp_core sp_bench_lib sp_dr_wav)
sp_set_warnings(sp-cli)

if(SP_BUILD_TESTS)
    # End-to-end smoke test: generate a hot sweep, process it, check the response command.
    set(_out "${CMAKE_CURRENT_BINARY_DIR}/smoke")
    add_test(NAME sp_cli_gen
        COMMAND sp-cli gen sweep "${_out}_in.wav" --seconds 2 --amp 0.9 --format s16)
    add_test(NAME sp_cli_process
        COMMAND sp-cli process "${_out}_in.wav" "${_out}_out.wav"
                --band lowshelf:80:12:0.7 --band peak:3000:9:1.0 --no-auto-preamp --ceiling -1)
    set_tests_properties(sp_cli_process PROPERTIES DEPENDS sp_cli_gen
        PASS_REGULAR_EXPRESSION "out: [^\n]*peak -1\\.0[0-9] dBFS")
    add_test(NAME sp_cli_response
        COMMAND sp-cli response --band peak:1000:6:1.0 --fs 48000 --points 3)
    set_tests_properties(sp_cli_response PROPERTIES
        PASS_REGULAR_EXPRESSION "effective_preamp_db=-6\\.0000")
endif()
