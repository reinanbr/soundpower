# sp-tone: plays a quiet tone as a media stream so that a postprocess effect attaches to it.
# For device tests only (scripts/device-test.sh); not part of the Magisk module.
add_executable(sp-tone sp_tone.cpp)
target_link_libraries(sp-tone PRIVATE aaudio m)
sp_set_warnings(sp-tone)
