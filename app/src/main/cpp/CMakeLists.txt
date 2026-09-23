cmake_minimum_required(VERSION 3.22.1)
project(credentials)

include(FetchContent)
FetchContent_Declare(
    mbedtls
    GIT_REPOSITORY https://github.com/Mbed-TLS/mbedtls.git
    GIT_TAG v3.6.2
    GIT_SHALLOW TRUE
)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(mbedtls)

add_library(credentials SHARED native_credentials.cpp credential_transform.cpp magis_crypto.cpp)

# Hide everything but JNI_OnLoad: the resolver functions are file-local statics, and this stops
# mbedTLS (and any inline) symbols from being re-exported, so the .so has nothing to grep for.
target_compile_options(credentials PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_options(credentials PRIVATE -Wl,--exclude-libs,ALL)

# Enforce "export ONLY JNI_OnLoad" at link time with a version script. -fvisibility=hidden alone is
# not enough once the kinomorf O-MVLL Virtualize pass runs (hardened release): it promotes the
# file-local virtualized kernels (des_block_kernel, ct_demask_kernel) to GLOBAL/DEFAULT visibility,
# which would re-export their (mangled) names from the .so and defeat the intent above. The version
# script pins the dynamic export set regardless of any per-symbol visibility the plugin sets, so the
# shipped .so exports ONLY JNI_OnLoad whether or not the plugin ran. Internal calls are unaffected
# (it only touches the dynamic symbol table, not internal linkage).
set(CRED_VERSION_SCRIPT "${CMAKE_CURRENT_BINARY_DIR}/credentials_exports.map")
file(WRITE "${CRED_VERSION_SCRIPT}" "{ global: JNI_OnLoad; local: *; };\n")
target_link_options(credentials PRIVATE "-Wl,--version-script=${CRED_VERSION_SCRIPT}")

find_library(log-lib log)

target_link_libraries(credentials mbedcrypto ${log-lib})

# 16 KB-aligned LOAD segments, explicit rather than relying on the NDK's default: newer Android
# devices use a 16 KB page size, and an unaligned segment fails to load there.
target_link_options(credentials PRIVATE "-Wl,-z,max-page-size=16384")
