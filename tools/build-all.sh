#!/usr/bin/env bash
set -Eeuo pipefail

npuhub_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
npuhub_jobs="${NPU_HUB_BUILD_JOBS:-2}"
host_arch="$(uname -m)"
llama_dir="${LLAMA_DIR:-${npuhub_root}/.rocket-runtime/llama.cpp}"
rocket_backend_dir="${npuhub_root}/.rocket-runtime/ggml-rocket"
rocket_userspace_dir="${npuhub_root}/.rocket-runtime/rocket-userspace"
rocket_build_dir="${npuhub_root}/workers/rocket/build"
rocket_patch="${npuhub_root}/workers/rocket/patches/llama-rocket-strict.patch"
native_resource_dir="${npuhub_root}/src/main/resources/native"

build_rocket_runtime=false
build_openvino_jni=false
build_qualcomm_jni=false
build_ryzenai_jni=false

case "${host_arch}" in
    x86_64|amd64)
        build_openvino_jni=true
        build_ryzenai_jni=true
        ;;
    aarch64|arm64)
        build_rocket_runtime=true
        build_qualcomm_jni=true
        ;;
    *)
        build_rocket_runtime=true
        build_openvino_jni=true
        build_qualcomm_jni=true
        build_ryzenai_jni=true
        ;;
esac

if [[ "${NPU_HUB_BUILD_ALL_PLATFORMS:-0}" == "1" ]]; then
    build_rocket_runtime=true
    build_openvino_jni=true
    build_qualcomm_jni=true
    build_ryzenai_jni=true
fi

log() {
    printf '[build-all] %s\n' "$*"
}

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        printf '[build-all] missing required command: %s\n' "$1" >&2
        exit 1
    fi
}

ensure_checkout() {
    local directory="$1"
    local repository="$2"
    local branch="$3"
    if [[ -d "${directory}/.git" ]]; then
        return
    fi
    if [[ -e "${directory}" ]]; then
        printf '[build-all] %s exists but is not a Git checkout\n' "${directory}" >&2
        exit 1
    fi
    mkdir -p "$(dirname -- "${directory}")"
    git clone --branch "${branch}" --single-branch "${repository}" "${directory}"
}

ensure_llama_patch() {
    if git -C "${llama_dir}" apply --reverse --check "${rocket_patch}" >/dev/null 2>&1; then
        log "Rocket scheduler patch already applied"
        return
    fi
    if git -C "${llama_dir}" apply --check "${rocket_patch}" >/dev/null 2>&1; then
        git -C "${llama_dir}" apply "${rocket_patch}"
        log "Applied Rocket scheduler patch"
        return
    fi
    printf '[build-all] Rocket patch is incompatible with current llama.cpp: %s\n' "${rocket_patch}" >&2
    exit 1
}

resolve_maven() {
    if [[ -x "${npuhub_root}/mvnw" ]]; then
        maven_command="${npuhub_root}/mvnw"
        return
    fi
    if command -v mvn >/dev/null 2>&1; then
        maven_command="$(command -v mvn)"
        return
    fi

    local version="${NPU_HUB_MAVEN_VERSION:-3.9.9}"
    local tool_root="${npuhub_root}/.build-tools"
    local install_dir="${tool_root}/apache-maven-${version}"
    local archive="${tool_root}/apache-maven-${version}-bin.tar.gz"
    if [[ ! -x "${install_dir}/bin/mvn" ]]; then
        require_command curl
        require_command tar
        mkdir -p "${tool_root}"
        log "Downloading Apache Maven ${version}"
        curl -fL --retry 3 \
            "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${version}/apache-maven-${version}-bin.tar.gz" \
            -o "${archive}"
        tar -xzf "${archive}" -C "${tool_root}"
    fi
    maven_command="${install_dir}/bin/mvn"
}

copy_runtime_library() {
    local source="$1"
    local destination="$2"
    if [[ ! -e "${source}" ]]; then
        printf '[build-all] expected runtime library not found: %s\n' "${source}" >&2
        exit 1
    fi
    cp -L -- "${source}" "${destination}"
}

require_command git
require_command cmake
require_command java

if [[ "${build_rocket_runtime}" == true ]]; then
    log "Updating llama.cpp to latest origin/master"
    ensure_checkout "${llama_dir}" "https://github.com/ggml-org/llama.cpp.git" master
    git -C "${llama_dir}" fetch --prune origin master
    git -C "${llama_dir}" rebase --autostash origin/master
    ensure_llama_patch

    log "Ensuring Rocket backend sources"
    ensure_checkout "${rocket_backend_dir}" "https://github.com/gregordinary/ggml-rocket.git" main
    ensure_checkout "${rocket_userspace_dir}" "https://github.com/gregordinary/rocket-userspace.git" main

    log "Building Rocket JNI and latest llama.cpp"
    cmake \
        -S "${npuhub_root}/workers/rocket" \
        -B "${rocket_build_dir}" \
        -DCMAKE_BUILD_TYPE=Release \
        -DLLAMA_DIR="${llama_dir}" \
        -DGGML_NATIVE=OFF \
        -DGGML_CPU_ALL_VARIANTS=ON \
        -DROCKET_BACKEND_LIBRARY="${rocket_backend_dir}/build-dl/libggml-rocket.so"
    cmake --build "${rocket_build_dir}" --parallel "${npuhub_jobs}"

    log "Building libggml-rocket.so against the same latest ggml ABI"
    cmake \
        -S "${rocket_backend_dir}" \
        -B "${rocket_backend_dir}/build-dl" \
        -DCMAKE_BUILD_TYPE=Release \
        -DGGML_ROCKET_DL=ON \
        -DHOST_DIR="${llama_dir}" \
        -DGGML_LIB_DIR="${rocket_build_dir}/bin" \
        -DCMAKE_DISABLE_FIND_PACKAGE_rocketnpu=ON \
        -DROCKETNPU_DIR="${rocket_userspace_dir}"
    cmake --build "${rocket_backend_dir}/build-dl" --parallel "${npuhub_jobs}"
    cp -L -- "${rocket_backend_dir}/build-dl/libggml-rocket.so" "${rocket_build_dir}/bin/libggml-rocket.so"
fi

log "Building generic JNI adapters"
cmake \
    -S "${npuhub_root}/native" \
    -B "${npuhub_root}/native/build" \
    -DCMAKE_BUILD_TYPE=Release \
    -DNPU_HUB_BUILD_OPENVINO_JNI="${build_openvino_jni}" \
    -DNPU_HUB_BUILD_ROCKCHIP_JNI="${build_rocket_runtime}" \
    -DNPU_HUB_BUILD_QUALCOMM_JNI="${build_qualcomm_jni}" \
    -DNPU_HUB_BUILD_RYZENAI_JNI="${build_ryzenai_jni}"
cmake --build "${npuhub_root}/native/build" --parallel "${npuhub_jobs}"

if [[ "${build_rocket_runtime}" == true ]]; then
    # The generic native project contains a compatibility Rockchip stub.
    # Replace it with the real Rocket/llama.cpp JNI runtime so source-tree
    # launches cannot accidentally load the stale stub before the bundled runtime.
    copy_runtime_library \
        "${rocket_build_dir}/bin/libnpu_rockchip_jni.so" \
        "${npuhub_root}/native/build/libnpu_rockchip_jni.so"
fi

log "Staging native libraries for the Spring Boot JAR"
mkdir -p "${native_resource_dir}/rocket"
if [[ "${build_openvino_jni}" == true ]]; then
    copy_runtime_library "${npuhub_root}/native/build/libnpu_openvino_jni.so" "${native_resource_dir}/libnpu_openvino_jni.so"
fi
if [[ "${build_qualcomm_jni}" == true ]]; then
    copy_runtime_library "${npuhub_root}/native/build/libnpu_qualcomm_jni.so" "${native_resource_dir}/libnpu_qualcomm_jni.so"
fi
if [[ "${build_ryzenai_jni}" == true ]]; then
    copy_runtime_library "${npuhub_root}/native/build/libnpu_ryzenai_jni.so" "${native_resource_dir}/libnpu_ryzenai_jni.so"
fi
if [[ "${build_rocket_runtime}" == true ]]; then
    copy_runtime_library "${rocket_build_dir}/bin/libnpu_rockchip_jni.so" "${native_resource_dir}/libnpu_rockchip_jni.so"
    copy_runtime_library "${rocket_build_dir}/bin/libggml-base.so.0" "${native_resource_dir}/rocket/libggml-base.so.0"
    copy_runtime_library "${rocket_build_dir}/bin/libggml.so.0" "${native_resource_dir}/rocket/libggml.so.0"
    copy_runtime_library "${rocket_build_dir}/bin/libllama.so.0" "${native_resource_dir}/rocket/libllama.so.0"
    copy_runtime_library "${rocket_build_dir}/bin/libggml-cpu.so" "${native_resource_dir}/rocket/libggml-cpu.so"
    copy_runtime_library "${rocket_build_dir}/bin/libggml-rocket.so" "${native_resource_dir}/rocket/libggml-rocket.so"
    copy_runtime_library "${rocket_build_dir}/bin/libnpu_rockchip_jni.so" "${native_resource_dir}/rocket/libnpu_rockchip_jni.so"
fi

resolve_maven
log "Building and testing the complete Spring Boot application"
"${maven_command}" -f "${npuhub_root}/pom.xml" clean package

log "Complete: ${npuhub_root}/target/npu-hub-1.0.0-SNAPSHOT.jar"
log "Run: java -jar target/npu-hub-1.0.0-SNAPSHOT.jar"
