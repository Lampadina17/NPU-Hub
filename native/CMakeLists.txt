cmake_minimum_required(VERSION 3.16)
project(npu_hub_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Find Java JNI headers
find_package(JNI REQUIRED)
include_directories(${JNI_INCLUDE_DIRS})
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/common)

message(STATUS "JNI Include Dirs: ${JNI_INCLUDE_DIRS}")

option(NPU_HUB_BUILD_OPENVINO_JNI "Build the OpenVINO JNI adapter" ON)
option(NPU_HUB_BUILD_ROCKCHIP_JNI "Build the Rockchip/Rocket JNI adapter" ON)
option(NPU_HUB_BUILD_QUALCOMM_JNI "Build the Qualcomm JNI adapter" ON)
option(NPU_HUB_BUILD_RYZENAI_JNI "Build the Ryzen AI JNI adapter" ON)

# 1. OpenVINO JNI Shared Library
if(NPU_HUB_BUILD_OPENVINO_JNI)
    add_library(npu_openvino_jni SHARED openvino/npu_openvino_jni.cpp)
    target_include_directories(npu_openvino_jni PRIVATE ${JNI_INCLUDE_DIRS} common)
endif()

# 2. Rockchip RKNN / Rocket JNI Shared Library
if(NPU_HUB_BUILD_ROCKCHIP_JNI)
    add_library(npu_rockchip_jni SHARED rockchip/npu_rockchip_jni.cpp)
    target_include_directories(npu_rockchip_jni PRIVATE ${JNI_INCLUDE_DIRS} common)
endif()

# 3. Qualcomm QAIRT / Genie JNI Shared Library
if(NPU_HUB_BUILD_QUALCOMM_JNI)
    add_library(npu_qualcomm_jni SHARED qualcomm/npu_qualcomm_jni.cpp)
    target_include_directories(npu_qualcomm_jni PRIVATE ${JNI_INCLUDE_DIRS} common)
endif()

# 4. AMD Ryzen AI JNI Shared Library
if(NPU_HUB_BUILD_RYZENAI_JNI)
    add_library(npu_ryzenai_jni SHARED ryzenai/npu_ryzenai_jni.cpp)
    target_include_directories(npu_ryzenai_jni PRIVATE ${JNI_INCLUDE_DIRS} common)
endif()
