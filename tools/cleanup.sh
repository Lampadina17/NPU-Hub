#!/usr/bin/env bash
set -Eeuo pipefail

npuhub_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cleanup_mode="builds"
assume_yes=false

usage() {
    cat <<'EOF'
Usage: tools/cleanup.sh [--builds|--downloads|--all] [--yes]

  --builds      Remove generated build output (default).
  --downloads   Remove re-downloadable tool/dependency caches.
  --all         Remove both generated output and re-downloadable caches.
  --yes         Do not ask for confirmation.

Models, configuration, logs, ggml-rocket sources, and rocket-userspace sources
are never removed.
EOF
}

for argument in "$@"; do
    case "${argument}" in
        --builds) cleanup_mode="builds" ;;
        --downloads) cleanup_mode="downloads" ;;
        --all) cleanup_mode="all" ;;
        --yes) assume_yes=true ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            printf 'Unknown argument: %s\n' "${argument}" >&2
            usage >&2
            exit 2
            ;;
    esac
done

build_targets=(
    "${npuhub_root}/target"
    "${npuhub_root}/native/build"
    "${npuhub_root}/workers/rocket/build"
    "${npuhub_root}/workers/openvino/build"
    "${npuhub_root}/workers/ryzenai/build"
    "${npuhub_root}/.rocket-runtime/ggml-rocket/build-dl"
    "${npuhub_root}/.rocket-runtime/rocket-userspace/build"
    "${npuhub_root}/src/main/resources/native"
)

download_targets=(
    "${npuhub_root}/.build-tools"
    "${npuhub_root}/.rocket-runtime/llama.cpp"
)

targets=()
if [[ "${cleanup_mode}" == "builds" || "${cleanup_mode}" == "all" ]]; then
    targets+=("${build_targets[@]}")
fi
if [[ "${cleanup_mode}" == "downloads" || "${cleanup_mode}" == "all" ]]; then
    targets+=("${download_targets[@]}")
fi

printf 'Cleanup mode: %s\n' "${cleanup_mode}"
printf 'Targets inside %s:\n' "${npuhub_root}"
for target in "${targets[@]}"; do
    [[ -e "${target}" ]] && printf '  %s\n' "${target}"
done

if [[ "${assume_yes}" != true ]]; then
    read -r -p 'Continue? [y/N] ' answer
    [[ "${answer}" == "y" || "${answer}" == "Y" ]] || {
        printf 'Cleanup cancelled.\n'
        exit 0
    }
fi

for target in "${targets[@]}"; do
    case "${target}" in
        "${npuhub_root}/"*)
            [[ "${target}" != "${npuhub_root}" ]] || {
                printf 'Refusing to remove workspace root.\n' >&2
                exit 1
            }
            ;;
        *)
            printf 'Refusing unsafe target: %s\n' "${target}" >&2
            exit 1
            ;;
    esac
    if [[ -e "${target}" ]]; then
        rm -rf -- "${target}"
        printf 'Removed %s\n' "${target}"
    fi
done

printf 'Cleanup complete. Models and source checkouts with Rocket patches were preserved.\n'
