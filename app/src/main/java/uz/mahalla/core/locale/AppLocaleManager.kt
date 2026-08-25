package uz.mahalla.core.locale

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Применение выбранного языка (эпик 1.5).
 *
 * API 33+ — системные per-app languages: система сама пересоздаёт Activity и
 * помнит выбор между запусками. API 26–32 — выбор живёт в DataStore, а
 * применяется через [LocaleContextWrapper] в `attachBaseContext`, поэтому
 * здесь достаточно сообщить вызывающему, что нужен recreate.
 */
interface AppLocaleManager {
    /** @return `true`, если Activity нужно пересоздать вручную (API < 33). */
    fun apply(language: AppLanguage): Boolean

    /** Язык, который реально применён системой (для API < 33 — `null`). */
    fun systemApplied(): AppLanguage?
}

@Singleton
class AndroidAppLocaleManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppLocaleManager {

    override fun apply(language: AppLanguage): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val localeManager = context.getSystemService(LocaleManager::class.java) ?: return true
        localeManager.applicationLocales = language.tag
            ?.let { LocaleList.forLanguageTags(it) }
            ?: LocaleList.getEmptyLocaleList()
        return false
    }

    override fun systemApplied(): AppLanguage? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val localeManager = context.getSystemService(LocaleManager::class.java) ?: return null
        val locales = localeManager.applicationLocales
        if (locales.isEmpty) return AppLanguage.SYSTEM
        return AppLanguage.fromTag(locales[0]?.toLanguageTag())
    }
}
