# effect-lib: libsoundpower.so, the Android audio effect that wraps dsp-core.

# ---- Core: instances, shared state, protocol codec, state file. Any compiler. ------------------
add_library(sp_effect_core STATIC
    src/codec.cpp
    src/state_file.cpp)
target_include_directories(sp_effect_core PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/src")
target_link_libraries(sp_effect_core PUBLIC sp_dsp_core sp_protocol)
target_compile_definitions(sp_effect_core PUBLIC
    SP_VERSION_MAJOR=${soundpower_VERSION_MAJOR}
    SP_VERSION_MINOR=${soundpower_VERSION_MINOR}
    SP_VERSION_PATCH=${soundpower_VERSION_PATCH})
set_target_properties(sp_effect_core PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON)
sp_set_warnings(sp_effect_core)
sp_realtime_target(sp_effect_core)
if(ANDROID)
    target_link_libraries(sp_effect_core PUBLIC log)
else()
    find_package(Threads REQUIRED)
    target_link_libraries(sp_effect_core PUBLIC Threads::Threads)
endif()
