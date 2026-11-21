# dr_wav: WAV decoding/encoding for host tools (and, in phase 6, IR loading).
add_library(sp_dr_wav STATIC dr_wav/dr_wav_impl.c)
add_library(soundpower::dr_wav ALIAS sp_dr_wav)
target_include_directories(sp_dr_wav SYSTEM PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/dr_wav")
set_target_properties(sp_dr_wav PROPERTIES POSITION_INDEPENDENT_CODE ON)

# AOSP audio effect headers: the ABI of libsoundpower.so. Header-only, SYSTEM so their warnings
# don't trip -Werror.
add_library(sp_aosp_headers INTERFACE)
add_library(soundpower::aosp_headers ALIAS sp_aosp_headers)
target_include_directories(sp_aosp_headers SYSTEM INTERFACE "${CMAKE_CURRENT_SOURCE_DIR}/aosp-headers/include")
