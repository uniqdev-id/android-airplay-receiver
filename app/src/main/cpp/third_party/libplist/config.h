/*
 * Hand-maintained config.h for the vendored libplist (libimobiledevice/libplist).
 *
 * Upstream generates this from autotools (AC_CONFIG_HEADERS([config.h]));
 * for the Android NDK build we keep it minimal:
 *   - PACKAGE_VERSION: required by plist.c (plist_get_version)
 *   - JSMN_PARENT_LINKS: parent links for JSON-XML parse tokens (matches
 *     jqssun/android-airplay-server's libplist setup)
 *   - HAVE_STRPTIME: bionic provides strptime() (used by xplist.c)
 *
 * time64 macros (USE_TM64/HAVE_TM_TM_GMTOFF/...): left undefined —
 * bionic is 64-bit time_t on all ABIs and has plain struct tm.
 */
#ifndef PLIST_CONFIG_H
#define PLIST_CONFIG_H

#define PACKAGE_VERSION "2.7.0"

#define JSMN_PARENT_LINKS
#define HAVE_STRPTIME 1
#define HAVE_STRNDUP   /* bionic provides strndup under _GNU_SOURCE */

/* #undef DEBUG */
/* #undef JSMN_STRICT */
/* #undef USE_TM */
/* #undef USE_TM64 */
/* #undef HAVE_TM_TM_GMTOFF */
/* #undef HAVE_TM_TM_ZONE */

#endif /* PLIST_CONFIG_H */
