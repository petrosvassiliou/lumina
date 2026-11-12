package app.lumina.player.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

enum class SupportedLuminaLocale(
    val languageTag: String,
    val nativeDisplayName: String
) {
    SYSTEM("", "System Default"),
    ENGLISH("en", "English"),
    GERMAN("de", "Deutsch"),
    ITALIAN("it", "Italiano"),
    FRENCH("fr", "Français"),
    SPANISH("es", "Español"),
    GREEK("el", "Ελληνικά");

    companion object {
        fun fromTag(tag: String): SupportedLuminaLocale {
            if (tag.isBlank()) return SYSTEM
            val normalized = tag.substringBefore('-').lowercase()
            return entries.firstOrNull { it.languageTag == normalized } ?: SYSTEM
        }
    }
}

/**
 * Applies per-app localization seamlessly via AndroidX `AppCompatDelegate.setApplicationLocales`
 * backed by `locales_config.xml` and `AppLocalesMetadataHolderService`.
 */
object LuminaLocaleManager {

    fun getCurrentLocale(): SupportedLuminaLocale {
        val currentList = AppCompatDelegate.getApplicationLocales()
        if (currentList.isEmpty) return SupportedLuminaLocale.SYSTEM
        val primaryTag = currentList.toLanguageTags().substringBefore(',')
        return SupportedLuminaLocale.fromTag(primaryTag)
    }

    fun applyLocale(locale: SupportedLuminaLocale) {
        val localeList = if (locale == SupportedLuminaLocale.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(locale.languageTag)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }
}
