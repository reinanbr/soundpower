# Host tests of the Magisk module scripts (the module itself is packaged by build.sh).
if(SP_BUILD_TESTS AND NOT CMAKE_CROSSCOMPILING)
    add_test(NAME sp_module_scripts
        COMMAND sh "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_scripts.sh" "$<TARGET_FILE:sp-xmlpatch>"
                "${PROJECT_SOURCE_DIR}/tools/xmlpatch/tests/fixtures")
    set_tests_properties(sp_module_scripts PROPERTIES SKIP_RETURN_CODE 77)
endif()
