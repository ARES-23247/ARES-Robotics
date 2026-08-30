package com.areslib.util

import java.security.MessageDigest

/** Canonical lowercase SHA-256 rendering for project-schema document identities. */
internal fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
