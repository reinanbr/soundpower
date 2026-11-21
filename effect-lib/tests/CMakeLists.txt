# Golden vectors (protocol/vectors/*.hex) are compiled into the tests, so they also run on a
# device, where the source tree isn't available.
file(GLOB _sp_vectors CONFIGURE_DEPENDS "${PROJECT_SOURCE_DIR}/protocol/vectors/*.hex")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${_sp_vectors})
set(_sp_vector_entries "")
foreach(path IN LISTS _sp_vectors)
    get_filename_component(name "${path}" NAME_WE)
    file(STRINGS "${path}" lines REGEX "^[^#]")
    list(JOIN lines " " hex)
    string(APPEND _sp_vector_entries "{\"${name}\", \"${hex}\"},\n")
endforeach()
file(CONFIGURE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/golden_vectors.inc"
    CONTENT "${_sp_vector_entries}")

set(_sp_test_includes
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${CMAKE_CURRENT_BINARY_DIR}"
    "${PROJECT_SOURCE_DIR}/dsp-core/tests")  # test_util.h (signals)

add_executable(sp_effect_tests
    test_codec.cpp
    test_concurrency.cpp
    test_global_state.cpp
    test_instance.cpp
    test_state_file.cpp)
target_include_directories(sp_effect_tests PRIVATE ${_sp_test_includes})
target_link_libraries(sp_effect_tests PRIVATE sp_effect_core GTest::gtest_main)
sp_set_warnings(sp_effect_tests)
sp_add_gtest(sp_effect_tests)

# Separate executable: it replaces the global allocation functions.
if(SP_ALLOCATION_TESTS)
    add_executable(sp_effect_rt_alloc_test test_rt_alloc.cpp)
    target_include_directories(sp_effect_rt_alloc_test PRIVATE ${_sp_test_includes})
    target_link_libraries(sp_effect_rt_alloc_test PRIVATE sp_effect_core GTest::gtest_main)
    sp_set_warnings(sp_effect_rt_alloc_test)
    sp_add_gtest(sp_effect_rt_alloc_test)
endif()

if(SP_HAVE_AELI)
    if(ANDROID)
        set(_sp_readelf_hints "${ANDROID_TOOLCHAIN_ROOT}/bin")
    endif()
    find_program(SP_READELF NAMES llvm-readelf readelf HINTS ${_sp_readelf_hints})
    if(SP_READELF)
        add_test(NAME sp_effect_elf_check
            COMMAND "${CMAKE_COMMAND}" "-DREADELF=${SP_READELF}" "-DLIB=$<TARGET_FILE:soundpower>"
                    "-DANDROID_ABI=${ANDROID_ABI}"
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/../cmake/check_elf.cmake")
    endif()
endif()
