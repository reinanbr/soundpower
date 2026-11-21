# sp-xmlpatch: adds SoundPower to the device's audio effects XML when the Magisk module installs.
add_library(sp_xmlpatch_core STATIC src/effects_xml.cpp)
target_include_directories(sp_xmlpatch_core PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/src")
target_link_libraries(sp_xmlpatch_core PUBLIC sp_tinyxml2 PRIVATE sp_protocol)
sp_set_warnings(sp_xmlpatch_core)

add_executable(sp-xmlpatch src/main.cpp)
target_link_libraries(sp-xmlpatch PRIVATE sp_xmlpatch_core)
sp_set_warnings(sp-xmlpatch)
if(ANDROID)
    # Runs inside the Magisk installer, possibly from recovery: depend on nothing on the device.
    target_link_options(sp-xmlpatch PRIVATE -static)
endif()
