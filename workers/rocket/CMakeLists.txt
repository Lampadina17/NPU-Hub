cmake_minimum_required(VERSION 3.16)
project(npu_hub_rocket_worker LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")

set(LLAMA_DIR "" CACHE PATH "Path to a llama.cpp checkout")
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Set LLAMA_DIR to a llama.cpp checkout")
endif()

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
# A dynamically loaded CPU backend cannot use host-specific GGML_NATIVE.
# Build dispatchable CPU variants instead, so the Rocket runtime and its
# ggml plugin use a compatible CPU backend configuration even when the build
# directory was previously configured on a different machine.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
find_package(JNI REQUIRED)
add_subdirectory("${LLAMA_DIR}" "${CMAKE_BINARY_DIR}/llama")

add_library(npu_rockchip_jni SHARED src/rocket_jni.cpp)
target_include_directories(npu_rockchip_jni PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(npu_rockchip_jni PRIVATE llama ${CMAKE_DL_LIBS})
set_target_properties(npu_rockchip_jni PROPERTIES
    BUILD_RPATH "\$ORIGIN;\$ORIGIN/bin"
    INSTALL_RPATH "\$ORIGIN"
)

set(
    ROCKET_BACKEND_LIBRARY
    ""
    CACHE FILEPATH
    "Path to the dynamically loaded libggml-rocket shared library"
)
if(
    NOT ROCKET_BACKEND_LIBRARY
    AND EXISTS "${CMAKE_SOURCE_DIR}/../../.rocket-runtime/ggml-rocket/build-dl/libggml-rocket.so"
)
    set(
        ROCKET_BACKEND_LIBRARY
        "${CMAKE_SOURCE_DIR}/../../.rocket-runtime/ggml-rocket/build-dl/libggml-rocket.so"
    )
endif()

add_custom_command(TARGET npu_rockchip_jni POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory ${CMAKE_SOURCE_DIR}/../../native/build
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:npu_rockchip_jni> ${CMAKE_BINARY_DIR}/bin/
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:npu_rockchip_jni> ${CMAKE_SOURCE_DIR}/../../native/build/
)

if(ROCKET_BACKEND_LIBRARY AND EXISTS "${ROCKET_BACKEND_LIBRARY}")
    add_custom_command(TARGET npu_rockchip_jni POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${ROCKET_BACKEND_LIBRARY}"
            "${CMAKE_BINARY_DIR}/bin/libggml-rocket.so"
        COMMENT "Bundling the Rocket GGML backend with the JNI runtime"
    )
else()
    message(WARNING
        "libggml-rocket.so was not found. Set ROCKET_BACKEND_LIBRARY before "
        "loading a model through the Rockchip JNI bridge."
    )
endif()
