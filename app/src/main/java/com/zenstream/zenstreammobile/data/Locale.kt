package com.zenstream.zenstreammobile.data

const val ENGLISH_LOCALE = "en"
const val JAPANESE_LOCALE = "ja"

enum class InterfaceLocaleMode(val storageValue: String) {
    Automatic("auto"),
    English(ENGLISH_LOCALE),
    Japanese(JAPANESE_LOCALE),
    ;

    companion object {
        fun fromStorageValue(value: String?): InterfaceLocaleMode =
            entries.firstOrNull { it.storageValue == value } ?: Automatic
    }
}

fun normalizeLocale(value: String?): String =
    when (value) {
        JAPANESE_LOCALE -> JAPANESE_LOCALE
        else -> ENGLISH_LOCALE
    }

fun isSupportedLocale(value: String?): Boolean = value == ENGLISH_LOCALE || value == JAPANESE_LOCALE

fun resolveInterfaceLocale(mode: InterfaceLocaleMode, systemLanguageTags: List<String>): String =
    when (mode) {
        InterfaceLocaleMode.English -> ENGLISH_LOCALE
        InterfaceLocaleMode.Japanese -> JAPANESE_LOCALE
        InterfaceLocaleMode.Automatic ->
            systemLanguageTags.firstNotNullOfOrNull { tag ->
                when (tag.trim().replace('_', '-').substringBefore('-').lowercase()) {
                    ENGLISH_LOCALE -> ENGLISH_LOCALE
                    JAPANESE_LOCALE -> JAPANESE_LOCALE
                    else -> null
                }
            } ?: ENGLISH_LOCALE
    }
