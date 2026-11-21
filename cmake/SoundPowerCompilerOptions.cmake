# Shared compiler settings for SoundPower's own targets (not third-party code).

# sp_set_warnings(<target>): strict warnings for first-party code.
function(sp_set_warnings target)
    if(MSVC)
        target_compile_options(${target} PRIVATE /W4 /permissive-)
        if(SP_WERROR)
            target_compile_options(${target} PRIVATE /WX)
        endif()
        return()
    endif()

    target_compile_options(${target} PRIVATE
        -Wall -Wextra -Wpedantic
        -Wshadow -Wnon-virtual-dtor -Wold-style-cast -Wcast-align
        -Woverloaded-virtual -Wnull-dereference -Wimplicit-fallthrough
        -Wfloat-conversion -Wdouble-promotion -Wformat=2)
    if(SP_WERROR)
        target_compile_options(${target} PRIVATE -Werror)
    endif()
endfunction()

# sp_realtime_target(<target>): code that may run inside an audio HAL process.
# No exceptions and no RTTI, so the same objects link into the Android effect.
function(sp_realtime_target target)
    if(NOT MSVC)
        target_compile_options(${target} PRIVATE -fno-exceptions -fno-rtti)
    endif()
endfunction()
