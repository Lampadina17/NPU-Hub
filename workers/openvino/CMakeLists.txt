cmake_minimum_required(VERSION 3.16)
project(genie_openvino_worker LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")

find_package(JNI REQUIRED)
find_package(OpenVINOGenAI REQUIRED)
find_package(OpenVINO REQUIRED)

add_library(npu_openvino_jni SHARED src/openvino_jni.cpp)
target_include_directories(npu_openvino_jni PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(npu_openvino_jni PRIVATE openvino::genai openvino::runtime)

add_custom_command(TARGET npu_openvino_jni POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory ${CMAKE_SOURCE_DIR}/../../native/build
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:npu_openvino_jni> ${CMAKE_SOURCE_DIR}/../../native/build/
)
