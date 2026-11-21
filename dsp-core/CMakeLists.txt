add_library(sp_dsp_core STATIC
    src/biquad.cpp
    src/format.cpp
    src/limiter.cpp
    src/limits.cpp
    src/parametric_eq.cpp
    src/processor.cpp
    src/rbj.cpp)
add_library(soundpower::dsp_core ALIAS sp_dsp_core)
target_include_directories(sp_dsp_core PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
target_compile_features(sp_dsp_core PUBLIC cxx_std_20)
set_target_properties(sp_dsp_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
sp_set_warnings(sp_dsp_core)
sp_realtime_target(sp_dsp_core)
