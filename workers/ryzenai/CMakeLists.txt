cmake_minimum_required(VERSION 3.20)
project(genie_ryzenai_worker LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")

set(RYZEN_AI_ROOT "$ENV{RYZEN_AI_INSTALLATION_PATH}" CACHE PATH "Ryzen AI Software root")
find_path(ONNXRUNTIME_GENAI_INCLUDE_DIR
  NAMES onnxruntime_genai_cxx_api.h
  HINTS "${RYZEN_AI_ROOT}" "${RYZEN_AI_ROOT}/deployment"
  PATH_SUFFIXES include deployment/include LLM/include)
find_library(ONNXRUNTIME_GENAI_LIBRARY
  NAMES onnxruntime-genai onnxruntime_genai
  HINTS "${RYZEN_AI_ROOT}" "${RYZEN_AI_ROOT}/deployment"
  PATH_SUFFIXES lib deployment/lib)

if(NOT ONNXRUNTIME_GENAI_INCLUDE_DIR OR NOT ONNXRUNTIME_GENAI_LIBRARY)
  message(FATAL_ERROR
    "ONNX Runtime GenAI C++ SDK not found. Set RYZEN_AI_INSTALLATION_PATH, "
    "ONNXRUNTIME_GENAI_INCLUDE_DIR, and ONNXRUNTIME_GENAI_LIBRARY.")
endif()

find_package(JNI REQUIRED)

add_library(npu_ryzenai_jni SHARED src/ryzenai_jni.cpp)
target_include_directories(npu_ryzenai_jni PRIVATE "${ONNXRUNTIME_GENAI_INCLUDE_DIR}" ${JNI_INCLUDE_DIRS})
target_link_libraries(npu_ryzenai_jni PRIVATE "${ONNXRUNTIME_GENAI_LIBRARY}")
set_target_properties(npu_ryzenai_jni PROPERTIES
  BUILD_RPATH "$ORIGIN/../../deployment/lib;$ORIGIN/../lib")

add_custom_command(TARGET npu_ryzenai_jni POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory ${CMAKE_SOURCE_DIR}/../../native/build
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:npu_ryzenai_jni> ${CMAKE_SOURCE_DIR}/../../native/build/
)
