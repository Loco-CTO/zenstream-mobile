package com.zenstream.zenstreammobile.data

const val ENGLISH_LOCALE = "en"
const val JAPANESE_LOCALE = "ja"

fun normalizeLocale(value: String?): String = when (value) {
    JAPANESE_LOCALE -> JAPANESE_LOCALE
    else -> ENGLISH_LOCALE
}

fun isSupportedLocale(value: String?): Boolean =
    value == ENGLISH_LOCALE || value == JAPANESE_LOCALE
