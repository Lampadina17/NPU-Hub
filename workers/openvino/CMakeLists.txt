cmake_minimum_required(VERSION 3.16)
project(genie_openvino_worker LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
list(APPEND CMAKE_PREFIX_PATH
    "${CMAKE_SOURCE_DIR}/../../.openvino-sdk/openvino_genai_ubuntu24_2025.4.0.0_x86_64/runtime/cmake"
    "${CMAKE_SOURCE_DIR}/../../.openvino-sdk/openvino_genai_ubuntu24_2025.4.0.0_x86_64"
)

find_package(JNI REQUIRED)
find_package(OpenVINOGenAI QUIET)
find_package(OpenVINO QUIET)

add_library(npu_openvino_jni SHARED src/openvino_jni.cpp)
target_include_directories(npu_openvino_jni PRIVATE ${JNI_INCLUDE_DIRS})

if(TARGET openvino::genai AND TARGET openvino::runtime)
    target_link_libraries(npu_openvino_jni PRIVATE openvino::genai openvino::runtime)
    message(STATUS "Linked OpenVINO GenAI & OpenVINO runtime to npu_openvino_jni")
else()
    message(STATUS "OpenVINOGenAI/OpenVINO package not found via CMAKE_PREFIX_PATH; building stub adapter")
endif()

add_custom_command(TARGET npu_openvino_jni POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory ${CMAKE_SOURCE_DIR}/../../native/build
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:npu_openvino_jni> ${CMAKE_SOURCE_DIR}/../../native/build/
)
