add_library(sp_protocol INTERFACE)
add_library(soundpower::protocol ALIAS sp_protocol)
target_include_directories(sp_protocol INTERFACE "${CMAKE_CURRENT_SOURCE_DIR}/include")
