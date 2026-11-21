# effect-lib: libsoundpower.so, the Android audio effect that wraps dsp-core.

# ---- Core: instances, shared state, protocol codec, state file. Any compiler. ------------------
add_library(sp_effect_core STATIC
    src/codec.cpp
    src/global_state.cpp
    src/instance.cpp
    src/log.cpp
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

# ---- AELI glue + the shared library. ------------------------------------------------------
# The AOSP headers only compile as C++ with Clang (see third_party/README.md). The NDK is Clang,
# so this only matters for desktop builds: with GCC, the core and its tests still build.
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    add_library(sp_effect_aeli OBJECT src/android/effect_api.cpp)
    target_link_libraries(sp_effect_aeli PUBLIC sp_effect_core sp_aosp_headers)
    set_target_properties(sp_effect_aeli PROPERTIES
        POSITION_INDEPENDENT_CODE ON
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)
    sp_set_warnings(sp_effect_aeli)
    sp_realtime_target(sp_effect_aeli)

    add_library(soundpower SHARED)
    target_link_libraries(soundpower PRIVATE sp_effect_aeli)
    set(_sp_exports "${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
    set_target_properties(soundpower PROPERTIES LINK_DEPENDS "${_sp_exports}")
    target_link_options(soundpower PRIVATE
        "LINKER:--version-script=${_sp_exports}"
        "LINKER:--exclude-libs,ALL"
        "LINKER:--gc-sections")
    if(ANDROID)
        # Loaded into the vendor audio HAL: no undefined symbols beyond libc/libm/libdl/liblog.
        target_link_options(soundpower PRIVATE "LINKER:--no-undefined")
    endif()
    set(SP_HAVE_AELI ON)
else()
    message(STATUS "effect-lib: libsoundpower.so and the AELI tests need Clang; "
                   "building the effect core only")
    set(SP_HAVE_AELI OFF)
endif()

if(SP_BUILD_TESTS)
    add_subdirectory(tests)
endif()
