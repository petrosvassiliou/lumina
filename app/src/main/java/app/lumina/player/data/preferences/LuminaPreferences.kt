package app.lumina.player.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.luminaDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lumina_preferences"
)

enum class HardwareAccelerationMode {
    AUTOMATIC,
    FULL_HARDWARE,
    DECODING_ONLY,
    SOFTWARE_FALLBACK
}

enum class ToneMappingAlgorithm(val vlcValue: String, val vlc4FunctionId: Int) {
    BT_2390("bt.2390", vlc4FunctionId = 2),
    HABLE("hable", vlc4FunctionId = 5),
    MOBIUS("mobius", vlc4FunctionId = 4),
    REINHARD("reinhard", vlc4FunctionId = 3)
}

enum class SubtitleFontScale(val scalePercent: Int, val displayLabel: String) {
    SMALL(80, "80%"),
    NORMAL(100, "100%"),
    LARGE(125, "125%"),
    EXTRA_LARGE(150, "150%")
}

enum class SubtitleBackgroundStyle(val vlcOpacity: Int) {
    NONE(0),
    TRANSLUCENT(128),
    SOLID(220)
}

data class UserPreferences(
    val hdrPassthroughEnabled: Boolean = true,
    val sdrToneMappingAlgorithm: ToneMappingAlgorithm = ToneMappingAlgorithm.BT_2390,
    val hardwareAccelerationMode: HardwareAccelerationMode = HardwareAccelerationMode.FULL_HARDWARE,
    val autoResumePlayback: Boolean = true,
    val autoEnterPipOnBackground: Boolean = true,
    val googlebookAlwaysOnTopMiniPlayer: Boolean = true,
    val doubleTapSeekSeconds: Int = 10,
    val networkCachingMs: Int = 1500,
    val defaultPlaybackSpeed: Float = 1.0f,
    val equalizerEnabled: Boolean = false,
    val equalizerPresetIndex: Int = 0,
    val equalizerPreAmpDb: Float = 0f,
    val equalizerBandsCsv: String = "0,0,0,0,0,0,0,0,0,0",
    val subtitleFontScale: SubtitleFontScale = SubtitleFontScale.NORMAL,
    val subtitleBackgroundStyle: SubtitleBackgroundStyle = SubtitleBackgroundStyle.NONE,
    val preferredAudioLanguage: String = "",
    val preferredSubtitleLanguage: String = "",
    val appLanguageTag: String = "",
    val pinnedFolderTreeUris: Set<String> = emptySet(),
    val autoCheckUpdatesEnabled: Boolean = true,
    val lastAutoUpdateCheckEpochMs: Long = 0L
) {
    fun parsedEqualizerBands(): List<Float> {
        val parsed = equalizerBandsCsv.split(",")
            .mapNotNull { it.trim().toFloatOrNull()?.coerceIn(-20f, 20f) }
        return if (parsed.size == 10) parsed else List(10) { 0f }
    }
}

@Singleton
class LuminaPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val HDR_PASSTHROUGH = booleanPreferencesKey("hdr_passthrough_enabled")
        val TONE_MAPPING_ALGO = stringPreferencesKey("sdr_tone_mapping_algorithm")
        val HW_ACCEL_MODE = stringPreferencesKey("hw_accel_mode")
        val AUTO_RESUME = booleanPreferencesKey("auto_resume_playback")
        val AUTO_PIP = booleanPreferencesKey("auto_enter_pip_on_background")
        val GOOGLEBOOK_MINI_PLAYER = booleanPreferencesKey("googlebook_always_on_top_mini_player")
        val DOUBLE_TAP_SEEK_SEC = intPreferencesKey("double_tap_seek_seconds")
        val NETWORK_CACHING_MS = intPreferencesKey("network_caching_ms")
        val DEFAULT_SPEED = floatPreferencesKey("default_playback_speed")
        val EQ_ENABLED = booleanPreferencesKey("equalizer_enabled")
        val EQ_PRESET_INDEX = intPreferencesKey("equalizer_preset_index")
        val EQ_PREAMP_DB = floatPreferencesKey("equalizer_preamp_db")
        val EQ_BANDS_CSV = stringPreferencesKey("equalizer_bands_csv")
        val SUBTITLE_FONT_SCALE = stringPreferencesKey("subtitle_font_scale")
        val SUBTITLE_BG_STYLE = stringPreferencesKey("subtitle_background_style")
        val PREFERRED_AUDIO_LANG = stringPreferencesKey("preferred_audio_lang")
        val PREFERRED_SUB_LANG = stringPreferencesKey("preferred_sub_lang")
        val APP_LANGUAGE_TAG = stringPreferencesKey("app_language_tag")
        val PINNED_FOLDER_TREES = stringSetPreferencesKey("pinned_folder_tree_uris")
        val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates_enabled")
        val LAST_AUTO_UPDATE_CHECK_MS = longPreferencesKey("last_auto_update_check_epoch_ms")
    }

    val userPreferencesFlow: Flow<UserPreferences> = context.luminaDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            val toneMapping = prefs[Keys.TONE_MAPPING_ALGO]?.let { name ->
                ToneMappingAlgorithm.entries.firstOrNull { it.name == name }
            } ?: ToneMappingAlgorithm.BT_2390

            val hwMode = prefs[Keys.HW_ACCEL_MODE]?.let { name ->
                HardwareAccelerationMode.entries.firstOrNull { it.name == name }
            } ?: HardwareAccelerationMode.FULL_HARDWARE

            val subScale = prefs[Keys.SUBTITLE_FONT_SCALE]?.let { name ->
                SubtitleFontScale.entries.firstOrNull { it.name == name }
            } ?: SubtitleFontScale.NORMAL

            val subBg = prefs[Keys.SUBTITLE_BG_STYLE]?.let { name ->
                SubtitleBackgroundStyle.entries.firstOrNull { it.name == name }
            } ?: SubtitleBackgroundStyle.NONE

            UserPreferences(
                hdrPassthroughEnabled = prefs[Keys.HDR_PASSTHROUGH] ?: true,
                sdrToneMappingAlgorithm = toneMapping,
                hardwareAccelerationMode = hwMode,
                autoResumePlayback = prefs[Keys.AUTO_RESUME] ?: true,
                autoEnterPipOnBackground = prefs[Keys.AUTO_PIP] ?: true,
                googlebookAlwaysOnTopMiniPlayer = prefs[Keys.GOOGLEBOOK_MINI_PLAYER] ?: true,
                doubleTapSeekSeconds = prefs[Keys.DOUBLE_TAP_SEEK_SEC] ?: 10,
                networkCachingMs = prefs[Keys.NETWORK_CACHING_MS] ?: 1500,
                defaultPlaybackSpeed = prefs[Keys.DEFAULT_SPEED] ?: 1.0f,
                equalizerEnabled = prefs[Keys.EQ_ENABLED] ?: false,
                equalizerPresetIndex = prefs[Keys.EQ_PRESET_INDEX] ?: 0,
                equalizerPreAmpDb = prefs[Keys.EQ_PREAMP_DB] ?: 0f,
                equalizerBandsCsv = prefs[Keys.EQ_BANDS_CSV] ?: "0,0,0,0,0,0,0,0,0,0",
                subtitleFontScale = subScale,
                subtitleBackgroundStyle = subBg,
                preferredAudioLanguage = prefs[Keys.PREFERRED_AUDIO_LANG] ?: "",
                preferredSubtitleLanguage = prefs[Keys.PREFERRED_SUB_LANG] ?: "",
                appLanguageTag = prefs[Keys.APP_LANGUAGE_TAG] ?: "",
                pinnedFolderTreeUris = prefs[Keys.PINNED_FOLDER_TREES] ?: emptySet(),
                autoCheckUpdatesEnabled = prefs[Keys.AUTO_CHECK_UPDATES] ?: true,
                lastAutoUpdateCheckEpochMs = prefs[Keys.LAST_AUTO_UPDATE_CHECK_MS] ?: 0L
            )
        }

    suspend fun setHdrPassthroughEnabled(enabled: Boolean) {
        context.luminaDataStore.edit { it[Keys.HDR_PASSTHROUGH] = enabled }
    }

    suspend fun setToneMappingAlgorithm(algorithm: ToneMappingAlgorithm) {
        context.luminaDataStore.edit { it[Keys.TONE_MAPPING_ALGO] = algorithm.name }
    }

    suspend fun setHardwareAccelerationMode(mode: HardwareAccelerationMode) {
        context.luminaDataStore.edit { it[Keys.HW_ACCEL_MODE] = mode.name }
    }

    suspend fun setAutoResumePlayback(enabled: Boolean) {
        context.luminaDataStore.edit { it[Keys.AUTO_RESUME] = enabled }
    }

    suspend fun setAutoEnterPipOnBackground(enabled: Boolean) {
        context.luminaDataStore.edit { it[Keys.AUTO_PIP] = enabled }
    }

    suspend fun setGooglebookAlwaysOnTopMiniPlayer(enabled: Boolean) {
        context.luminaDataStore.edit { it[Keys.GOOGLEBOOK_MINI_PLAYER] = enabled }
    }

    suspend fun setDoubleTapSeekSeconds(seconds: Int) {
        context.luminaDataStore.edit { it[Keys.DOUBLE_TAP_SEEK_SEC] = seconds.coerceIn(5, 60) }
    }

    suspend fun setNetworkCachingMs(cachingMs: Int) {
        context.luminaDataStore.edit { it[Keys.NETWORK_CACHING_MS] = cachingMs.coerceIn(300, 10_000) }
    }

    suspend fun setSubtitleFontScale(scale: SubtitleFontScale) {
        context.luminaDataStore.edit { it[Keys.SUBTITLE_FONT_SCALE] = scale.name }
    }

    suspend fun setSubtitleBackgroundStyle(style: SubtitleBackgroundStyle) {
        context.luminaDataStore.edit { it[Keys.SUBTITLE_BG_STYLE] = style.name }
    }

    suspend fun setEqualizerConfiguration(
        enabled: Boolean,
        presetIndex: Int,
        preAmpDb: Float,
        bandGainsDb: List<Float>
    ) {
        val normalizedBands = (0 until 10).map { idx ->
            (bandGainsDb.getOrNull(idx) ?: 0f).coerceIn(-20f, 20f)
        }
        context.luminaDataStore.edit { prefs ->
            prefs[Keys.EQ_ENABLED] = enabled
            prefs[Keys.EQ_PRESET_INDEX] = presetIndex
            prefs[Keys.EQ_PREAMP_DB] = preAmpDb.coerceIn(-20f, 20f)
            prefs[Keys.EQ_BANDS_CSV] = normalizedBands.joinToString(",")
        }
    }

    suspend fun setAppLanguageTag(languageTag: String) {
        context.luminaDataStore.edit { it[Keys.APP_LANGUAGE_TAG] = languageTag }
    }

    suspend fun addPinnedFolderTreeUri(uriString: String) {
        context.luminaDataStore.edit { prefs ->
            val current = prefs[Keys.PINNED_FOLDER_TREES] ?: emptySet()
            prefs[Keys.PINNED_FOLDER_TREES] = current + uriString
        }
    }

    suspend fun removePinnedFolderTreeUri(uriString: String) {
        context.luminaDataStore.edit { prefs ->
            val current = prefs[Keys.PINNED_FOLDER_TREES] ?: emptySet()
            prefs[Keys.PINNED_FOLDER_TREES] = current - uriString
        }
    }

    suspend fun setAutoCheckUpdatesEnabled(enabled: Boolean) {
        context.luminaDataStore.edit { it[Keys.AUTO_CHECK_UPDATES] = enabled }
    }

    suspend fun setLastAutoUpdateCheckEpochMs(epochMs: Long) {
        context.luminaDataStore.edit { it[Keys.LAST_AUTO_UPDATE_CHECK_MS] = epochMs }
    }
}


