# "Hey Muse" detector for Android: the same sources and settings as
# muse-gadget-sdk/linux/wakeword/build.sh, built with the NDK.
cmake_minimum_required(VERSION 3.22.1)
project(muse_wakeword C CXX ASM)

set(WW "${CMAKE_CURRENT_SOURCE_DIR}/muse_wakeword")
set(DEPS "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/wakeword")
set(ET "${DEPS}/executorch")
set(NN "${DEPS}/esp-nn")
set(MF "${DEPS}/esp-micro-speech-features")
if(NOT EXISTS "${ET}/et_generated.cmake" OR NOT EXISTS "${NN}/src" OR NOT EXISTS "${MF}/src")
  message(FATAL_ERROR "Dépendances absentes : lancer ./tools/prepare-wakeword.sh")
endif()

# The list of ExecuTorch sources is read as text, without running their CMake file.
file(READ "${ET}/et_generated.cmake" generated)
function(et_sources name out)
  string(REGEX MATCH "set\\(${name}[^)]*\\)" block "${generated}")
  string(REGEX MATCHALL "\"src/[^\"]*\"" quoted "${block}")
  set(files)
  foreach(item IN LISTS quoted)
    string(REPLACE "\"" "" item "${item}")
    list(APPEND files "${ET}/${item}")
  endforeach()
  set(${out} "${files}" PARENT_SCOPE)
endfunction()
et_sources(ET_CORE_SRCS et_core)
et_sources(ET_ESPNN_SRCS et_espnn)
list(LENGTH et_core core_count)
list(LENGTH et_espnn espnn_count)
if(core_count LESS 11 OR NOT espnn_count EQUAL 3)
  message(FATAL_ERROR "Listes de sources ExecuTorch inattendues (${core_count}, ${espnn_count})")
endif()

set(common -O2 -ffunction-sections -fdata-sections -DNDEBUG -w)
set(cxxonly -std=c++17 -fno-exceptions -fno-rtti -fno-threadsafe-statics)
set(et_defines C10_USING_CUSTOM_GENERATED_MACROS ET_USE_BUILTIN_FUNCTION_NAME=0
    FLATBUFFERS_MAX_ALIGNMENT=1024 ET_ENABLE_PROGRAM_VERIFICATION=1 ET_LOG_ENABLED=0 ET_MIN_LOG_LEVEL=Error)
set(et_includes "${ET}/include" "${ET}/priv_include")
set(nn_includes "${NN}/include" "${NN}/src/common")

add_library(ww_executorch OBJECT ${et_core} ${et_espnn} "${WW}/host/et_pal.cpp")
target_include_directories(ww_executorch PRIVATE "${WW}/host" ${et_includes} ${nn_includes})
target_compile_definitions(ww_executorch PRIVATE ${et_defines})
target_compile_options(ww_executorch PRIVATE ${common} ${cxxonly})

# The portable C kernels of esp-nn.
file(GLOB nn_sources "${NN}/src/*/*_ansi.c")
list(APPEND nn_sources
  "${NN}/src/convolution/esp_nn_conv_opt.c" "${NN}/src/convolution/esp_nn_depthwise_conv_opt.c"
  "${NN}/src/softmax/esp_nn_softmax_opt.c" "${NN}/src/common/esp_nn_multicore.c")
add_library(ww_espnn OBJECT ${nn_sources})
target_include_directories(ww_espnn PRIVATE "${WW}/host" ${nn_includes})
target_compile_definitions(ww_espnn PRIVATE CONFIG_NN_OPTIMIZED=1)
target_compile_options(ww_espnn PRIVATE ${common} -std=c11)

file(GLOB mf_sources "${MF}/src/*.c")
add_library(ww_microfrontend OBJECT ${mf_sources})
target_include_directories(ww_microfrontend PRIVATE "${MF}/include" "${MF}/src")
target_compile_options(ww_microfrontend PRIVATE ${common} -std=c11)

add_library(ww_signal OBJECT "${WW}/ww_frontend.c" "${WW}/ww_detector.c")
target_include_directories(ww_signal PRIVATE "${WW}" "${WW}/include" "${MF}/include")
target_compile_options(ww_signal PRIVATE ${common} -std=c11 -ffp-contract=off)

add_library(ww_runner OBJECT "${WW}/ww_runner_espnn.cpp")
target_include_directories(ww_runner PRIVATE "${WW}" "${WW}/include" ${et_includes})
target_compile_definitions(ww_runner PRIVATE ${et_defines})
target_compile_options(ww_runner PRIVATE ${common} ${cxxonly})

# The model, embedded in the library and aligned on 16 bytes as in the firmware.
set(HEY_MUSE_PTE_PATH "${WW}/models/hey_muse.pte")
file(SIZE "${HEY_MUSE_PTE_PATH}" HEY_MUSE_PTE_LEN)
configure_file("${WW}/hey_muse_pte.S.in" "${CMAKE_CURRENT_BINARY_DIR}/hey_muse_pte.S" @ONLY)

foreach(part ww_executorch ww_espnn ww_microfrontend ww_signal ww_runner)
  set_target_properties(${part} PROPERTIES POSITION_INDEPENDENT_CODE ON)
endforeach()

add_library(muse_wakeword SHARED ww_jni.c "${CMAKE_CURRENT_BINARY_DIR}/hey_muse_pte.S"
  $<TARGET_OBJECTS:ww_executorch> $<TARGET_OBJECTS:ww_espnn> $<TARGET_OBJECTS:ww_microfrontend>
  $<TARGET_OBJECTS:ww_signal> $<TARGET_OBJECTS:ww_runner>)
target_include_directories(muse_wakeword PRIVATE "${WW}/include")
target_compile_options(muse_wakeword PRIVATE $<$<COMPILE_LANGUAGE:C>:-O2>)
set_target_properties(muse_wakeword PROPERTIES LINKER_LANGUAGE CXX)
target_link_options(muse_wakeword PRIVATE
  "-Wl,--gc-sections" "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map" "-Wl,-z,defs")
target_link_libraries(muse_wakeword m)
