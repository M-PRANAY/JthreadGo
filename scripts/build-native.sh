#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
jdk_home="${1:-${JAVA_HOME:-}}"
output_directory="${2:-$project_root/target/native}"
headers_directory="${3:-$project_root/target/generated-native-headers}"

if [[ -z "$jdk_home" ]]; then
    printf '%s\n' 'Set JAVA_HOME to a JDK 25 installation, or pass its path as the first argument.' >&2
    exit 1
fi

case "$(uname -s)" in
    Linux) jni_platform=linux; library_name=libjthreadgo.so; linker_options=(-shared) ;;
    Darwin) jni_platform=darwin; library_name=libjthreadgo.dylib; linker_options=(-dynamiclib) ;;
    *) printf '%s\n' 'This script supports Linux and macOS. Use build-native.ps1 on Windows.' >&2; exit 1 ;;
esac

for required in \
    "$jdk_home/include/jvmti.h" \
    "$jdk_home/include/$jni_platform/jni_md.h" \
    "$headers_directory/org_jthreadgo_JthreadGo.h" \
    "$project_root/native/jthreadgo.cpp"; do
    if [[ ! -f "$required" ]]; then
        printf 'Missing build input: %s. Run Maven compile before this script.\n' "$required" >&2
        exit 1
    fi
done

# CXX is one executable path; arguments are kept separate to preserve spaces.
compiler="${CXX:-c++}"
if ! command -v "$compiler" >/dev/null 2>&1; then
    printf 'C++ compiler not found: %s. Install GCC/Clang or the Xcode command-line tools.\n' "$compiler" >&2
    exit 1
fi
mkdir -p -- "$output_directory"
"$compiler" -std=c++17 -O2 -fPIC -Wall -Wextra -Wpedantic \
    -I"$jdk_home/include" -I"$jdk_home/include/$jni_platform" -I"$headers_directory" \
    "${linker_options[@]}" "$project_root/native/jthreadgo.cpp" \
    -o "$output_directory/$library_name"
printf 'Built %s\n' "$output_directory/$library_name"
