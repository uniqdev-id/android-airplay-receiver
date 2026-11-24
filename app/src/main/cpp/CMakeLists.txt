# Phase 3: UxPlay AirPlay core (vendored) + libplist + OpenSSL + JNI bridge.
cmake_minimum_required(VERSION 3.22.1)
project(airplayjni C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)

set(UXP third_party/UxPlay/lib)

# --- UxPlay core (GStreamer/renderers from the app tree are NOT vendored) ---
set(UXPLAY_SOURCES
    ${UXP}/airplay_video.c
    ${UXP}/byteutils.c
    ${UXP}/compat.c
    ${UXP}/crypto.c
    ${UXP}/dnssd.c
    ${UXP}/fairplay_playfair.c
    ${UXP}/http_request.c
    ${UXP}/http_response.c
    ${UXP}/httpd.c
    ${UXP}/logger.c
    ${UXP}/mirror_buffer.c
    ${UXP}/netutils.c
    ${UXP}/pairing.c
    ${UXP}/raop.c
    ${UXP}/raop_buffer.c
    ${UXP}/raop_ntp.c
    ${UXP}/raop_rtp.c
    ${UXP}/raop_rtp_mirror.c
    ${UXP}/srp.c
    ${UXP}/utils.c
    ${UXP}/fairplay_v2/fairplay_v2.c
    ${UXP}/llhttp/api.c
    ${UXP}/llhttp/http.c
    ${UXP}/llhttp/llhttp.c
    ${UXP}/mdnsd/mdnsd.c
    ${UXP}/mdnsd/dnssd_mdnsd.c
    ${UXP}/playfair/hand_garble.c
    ${UXP}/playfair/modified_md5.c
    ${UXP}/playfair/omg_hax.c
    ${UXP}/playfair/playfair.c
    ${UXP}/playfair/sap_hash.c
)

# --- libplist (C only; from libimobiledevice/libplist) ---
set(PLIST third_party/libplist)
set(OPENSSL third_party/openssl)
set(PLIST_SOURCES
    ${PLIST}/src/base64.c
    ${PLIST}/src/bplist.c
    ${PLIST}/src/bytearray.c
    ${PLIST}/src/common.c
    ${PLIST}/src/hashtable.c
    ${PLIST}/src/jplist.c
    ${PLIST}/src/jsmn.c
    ${PLIST}/src/oplist.c
    ${PLIST}/src/out-default.c
    ${PLIST}/src/out-limd.c
    ${PLIST}/src/out-plutil.c
    ${PLIST}/src/plist.c
    ${PLIST}/src/ptrarray.c
    ${PLIST}/src/time64.c
    ${PLIST}/src/xplist.c
    ${PLIST}/libcnary/cnary.c
    ${PLIST}/libcnary/node.c
    ${PLIST}/libcnary/node_list.c
)

add_library(airplayjni SHARED
    airplay_jni.cpp
    ${UXPLAY_SOURCES}
    ${PLIST_SOURCES}
)

target_include_directories(airplayjni PRIVATE
    ${UXP}
    ${UXP}/llhttp
    ${UXP}/playfair
    ${PLIST}/include
    ${PLIST}/libcnary/include
    ${PLIST}/src       # private headers (hashtable.h, ptrarray.h, ...)
    ${PLIST}            # config.h (hand-maintained; see file header)
    ${OPENSSL}/include
)

target_compile_definitions(airplayjni PRIVATE
    __STDC_CONSTANT_MACROS
    __STDC_LIMIT_MACROS
    PLIST_210           # libplist 2.1+ API branch in UxPlay's http_handlers.h
    HAVE_CONFIG_H       # libplist guards every `#include <config.h>` on this
    _GNU_SOURCE
    OPENSSL_API_COMPAT=0x10101000L
)

# OpenSSL 1.1.1q (Google's NDK thirdparty prefab, extracted):
#   build-time: link the ABI slice of the shared libs below
#   runtime:    the same .so files are packaged from app/src/main/jniLibs,
#               so the app's native library dir satisfies the NEEDED entries.
add_library(ussl IMPORTED SHARED)
add_library(ucrypto IMPORTED SHARED)
set_target_properties(ussl PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_LIST_DIR}/${OPENSSL}/lib/${ANDROID_ABI}/libssl.so)
set_target_properties(ucrypto PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_LIST_DIR}/${OPENSSL}/lib/${ANDROID_ABI}/libcrypto.so)

target_link_libraries(airplayjni
    ussl
    ucrypto
    log
)

target_link_options(airplayjni PRIVATE "-Wl,--build-id=none")
