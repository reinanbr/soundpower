# Checks the shape of libsoundpower.so (run with cmake -P):
#   - it exports exactly one defined dynamic symbol, AELI;
#   - on Android, it needs no library outside libc, libm, libdl and liblog (anything else may be
#     invisible to the vendor linker namespace of the audio HAL);
#   - on 64-bit Android ABIs, every LOAD segment is aligned for 16 KB pages.
#
# Variables: READELF, LIB, ANDROID_ABI (empty for desktop builds).

foreach(var READELF LIB)
    if(NOT ${var})
        message(FATAL_ERROR "check_elf.cmake: ${var} is not set")
    endif()
endforeach()

function(sp_readelf out)
    execute_process(COMMAND "${READELF}" ${ARGN} "${LIB}"
        OUTPUT_VARIABLE text ERROR_VARIABLE err RESULT_VARIABLE rc)
    if(NOT rc EQUAL 0)
        message(FATAL_ERROR "${READELF} ${ARGN} failed: ${err}")
    endif()
    set(${out} "${text}" PARENT_SCOPE)
endfunction()

set(failures "")

# ---- Exported symbols ----------------------------------------------------------------------
sp_readelf(syms --wide --dyn-syms)
string(REPLACE "\n" ";" lines "${syms}")
set(exported "")
foreach(line IN LISTS lines)
    # Num: Value Size Type Bind Vis Ndx Name
    if(line MATCHES "^ *[0-9]+: +[0-9a-fA-F]+ +[0-9]+ +[A-Z_]+ +(GLOBAL|WEAK) +[A-Z_]+ +([A-Z0-9]+) +([^ @]+)")
        if(NOT CMAKE_MATCH_2 STREQUAL "UND")
            list(APPEND exported "${CMAKE_MATCH_3}")
        endif()
    endif()
endforeach()
if(NOT exported STREQUAL "AELI")
    list(APPEND failures "exported symbols are [${exported}], expected [AELI]")
endif()

if(ANDROID_ABI)
    # ---- Needed libraries ----------------------------------------------------------------
    sp_readelf(dyn --dynamic)
    string(REGEX MATCHALL "Shared library: \\[[^]]+\\]" needed_entries "${dyn}")
    set(needed "")
    foreach(entry IN LISTS needed_entries)
        string(REGEX REPLACE "Shared library: \\[([^]]+)\\]" "\\1" lib "${entry}")
        list(APPEND needed "${lib}")
        if(NOT lib MATCHES "^lib(c|m|dl|log)\\.so$")
            list(APPEND failures "unexpected NEEDED library ${lib}")
        endif()
    endforeach()

    # ---- Page alignment ------------------------------------------------------------------
    if(ANDROID_ABI MATCHES "64")
        sp_readelf(segments --wide --program-headers)
        string(REGEX MATCHALL "LOAD[^\n]*" loads "${segments}")
        foreach(load IN LISTS loads)
            string(REGEX MATCH "0x[0-9a-fA-F]+$" align "${load}")
            math(EXPR align_value "${align}")
            if(align_value LESS 16384)
                list(APPEND failures "LOAD segment aligned to ${align}, need 0x4000: ${load}")
            endif()
        endforeach()
    endif()
endif()

if(failures)
    list(JOIN failures "\n  " report)
    message(FATAL_ERROR "libsoundpower.so check failed:\n  ${report}")
endif()
message(STATUS "libsoundpower.so: exports [${exported}], needs [${needed}]")
