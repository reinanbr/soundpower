# Tools that ship in the Magisk module or help test on a device.
add_subdirectory(xmlpatch)
if(ANDROID AND SP_BUILD_TOOLS)
    add_subdirectory(sp-tone)
endif()
