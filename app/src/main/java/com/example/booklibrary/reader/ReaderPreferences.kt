@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.example.booklibrary.reader

import android.content.Context
import android.graphics.Color
import com.example.booklibrary.tts.TtsModelManager
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.util.Language
import java.util.Locale

class ReaderPreferences(context: Context) {

    private val displayPrefs = context.getSharedPreferences("reader_display_prefs", Context.MODE_PRIVATE)
    private val ttsPrefs = context.getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)

    // Display
    var fontFamily: FontFamily? = null; private set
    var fontSize: Double = 1.0; private set
    var theme: Theme? = null; private set

    // TTS
    var ttsSpeed: Double = 1.0; private set
    var ttsPitch: Double = 1.0; private set
    var ttsVoiceId: String? = null; private set
    var ttsVoiceLang: String? = null; private set
    var highlightColor: String = "blue"; private set
    var ttsEngine: String = "piper"; private set
    var selectedPiperModelId: String = TtsModelManager.PIPER_MODELS.first().id; private set
    var selectedKokoroSpeakerId: Int = 0; private set
    var selectedKokoroModelId: String = TtsModelManager.KOKORO_MODELS.first().id; private set
    var kokoroPreloadEnabled: Boolean = false; private set

    init {
        loadDisplayPreferences()
        loadTtsPreferences()
    }

    // --- Display ---

    private fun loadDisplayPreferences() {
        fontSize = displayPrefs.getFloat("font_size", 1.0f).toDouble()
        val themeName = displayPrefs.getString("theme", null)
        theme = when (themeName) {
            "dark" -> Theme.DARK
            "sepia" -> Theme.SEPIA
            "light" -> Theme.LIGHT
            else -> null
        }
        val fontFamilyName = displayPrefs.getString("font_family", null)
        fontFamily = when (fontFamilyName) {
            "serif" -> FontFamily.SERIF
            "sans-serif" -> FontFamily.SANS_SERIF
            "cursive" -> FontFamily("cursive")
            "monospace" -> FontFamily("monospace")
            else -> null
        }
    }

    fun updateDisplay(fontFamily: FontFamily?, fontSize: Double, theme: Theme?) {
        this.fontFamily = fontFamily
        this.fontSize = fontSize
        this.theme = theme
        saveDisplayPreferences()
    }

    private fun saveDisplayPreferences() {
        val fontName = when (fontFamily) {
            FontFamily.SERIF -> "serif"
            FontFamily.SANS_SERIF -> "sans-serif"
            FontFamily("cursive") -> "cursive"
            FontFamily("monospace") -> "monospace"
            else -> null
        }
        val themeName = when (theme) {
            Theme.DARK -> "dark"
            Theme.SEPIA -> "sepia"
            Theme.LIGHT -> "light"
            else -> null
        }
        displayPrefs.edit()
            .putFloat("font_size", fontSize.toFloat())
            .putString("theme", themeName)
            .putString("font_family", fontName)
            .apply()
    }

    fun buildEpubPreferences(): EpubPreferences {
        return EpubPreferences(
            fontFamily = fontFamily,
            fontSize = fontSize,
            theme = theme,
            publisherStyles = (fontFamily == null)
        )
    }

    // --- TTS ---

    private fun loadTtsPreferences() {
        ttsSpeed = ttsPrefs.getFloat("tts_speed", 1.0f).toDouble()
        ttsPitch = ttsPrefs.getFloat("tts_pitch", 1.0f).toDouble()
        ttsVoiceId = ttsPrefs.getString("tts_voice_id", null)
        ttsVoiceLang = ttsPrefs.getString("tts_voice_lang", null)
        highlightColor = ttsPrefs.getString("highlight_color", "blue") ?: "blue"
        ttsEngine = ttsPrefs.getString("tts_engine", "piper") ?: "piper"
        selectedPiperModelId = ttsPrefs.getString("piper_model_id", TtsModelManager.PIPER_MODELS.first().id)
            ?: TtsModelManager.PIPER_MODELS.first().id
        selectedKokoroSpeakerId = ttsPrefs.getInt("kokoro_speaker_id", 0)
        selectedKokoroModelId = ttsPrefs.getString("kokoro_model_id", TtsModelManager.KOKORO_MODELS.first().id)
            ?: TtsModelManager.KOKORO_MODELS.first().id
        kokoroPreloadEnabled = ttsPrefs.getBoolean("kokoro_preload_enabled", false)
    }

    fun updateTtsSpeed(speed: Double) {
        ttsSpeed = speed
        saveTtsPreferences()
    }

    fun updateTtsPitch(pitch: Double) {
        ttsPitch = pitch
        saveTtsPreferences()
    }

    fun updateTtsVoice(voiceId: String, voiceLang: String) {
        ttsVoiceId = voiceId
        ttsVoiceLang = voiceLang
        saveTtsPreferences()
    }

    fun updateTtsEngine(engine: String) {
        ttsEngine = engine
        saveTtsPreferences()
    }

    fun updateHighlightColor(color: String) {
        highlightColor = color
        saveTtsPreferences()
    }

    fun updatePiperModel(modelId: String) {
        selectedPiperModelId = modelId
        saveTtsPreferences()
    }

    fun updateKokoroSpeaker(speakerId: Int) {
        selectedKokoroSpeakerId = speakerId
        saveTtsPreferences()
    }

    fun updateKokoroModel(modelId: String) {
        selectedKokoroModelId = modelId
        selectedKokoroSpeakerId = 0
        saveTtsPreferences()
    }

    fun updateKokoroPreloadEnabled(enabled: Boolean) {
        kokoroPreloadEnabled = enabled
        saveTtsPreferences()
    }

    private fun saveTtsPreferences() {
        ttsPrefs.edit()
            .putFloat("tts_speed", ttsSpeed.toFloat())
            .putFloat("tts_pitch", ttsPitch.toFloat())
            .putString("tts_voice_id", ttsVoiceId)
            .putString("tts_voice_lang", ttsVoiceLang)
            .putString("highlight_color", highlightColor)
            .putString("tts_engine", ttsEngine)
            .putString("piper_model_id", selectedPiperModelId)
            .putInt("kokoro_speaker_id", selectedKokoroSpeakerId)
            .putString("kokoro_model_id", selectedKokoroModelId)
            .putBoolean("kokoro_preload_enabled", kokoroPreloadEnabled)
            .apply()
    }

    fun resolveHighlightColor(): Int {
        return when (highlightColor) {
            "red" -> Color.parseColor("#66F44336")
            "yellow" -> Color.parseColor("#66FFEB3B")
            else -> Color.parseColor("#664FC3F7")
        }
    }

    fun buildAndroidTtsPreferences(): AndroidTtsPreferences {
        val voiceMap = if (ttsVoiceId != null && ttsVoiceLang != null) {
            val lang = Language(Locale.forLanguageTag(ttsVoiceLang!!))
            mapOf(lang to AndroidTtsEngine.Voice.Id(ttsVoiceId!!))
        } else null

        return AndroidTtsPreferences(
            speed = ttsSpeed,
            pitch = ttsPitch,
            voices = voiceMap
        )
    }

}
