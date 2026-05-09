@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.example.booklibrary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import kotlin.math.roundToInt

class DisplaySettingsSheet : BottomSheetDialogFragment() {

    interface Host {
        fun getDisplayFontFamily(): FontFamily?
        fun getDisplayFontSize(): Double
        fun getDisplayTheme(): Theme?
        fun getHighlightColor(): String
        fun onDisplayPreferencesChanged(fontFamily: FontFamily?, fontSize: Double, theme: Theme?)
        fun onHighlightColorChanged(color: String)
    }

    companion object {
        private val FONT_CURSIVE = FontFamily("cursive")
        private val FONT_MONOSPACE = FontFamily("monospace")

        fun newInstance(): DisplaySettingsSheet = DisplaySettingsSheet()
    }

    var onPreferencesChanged: ((fontFamily: FontFamily?, fontSize: Double, theme: Theme?) -> Unit)? = null
    var onHighlightColorChanged: ((String) -> Unit)? = null

    private var currentFontSize: Double = 1.0
    private var currentFontFamily: FontFamily? = null
    private var currentTheme: Theme? = null
    private var currentHighlightColor: String = "blue"
    private var initializedFromHost = false

    fun setCurrentPreferences(fontFamily: FontFamily?, fontSize: Double, theme: Theme?, highlightColor: String = "blue") {
        currentFontFamily = fontFamily
        currentFontSize = fontSize
        currentTheme = theme
        currentHighlightColor = highlightColor
        initializedFromHost = false
    }

    private fun restoreFromHost() {
        val host = activity as? Host ?: return
        if (initializedFromHost) return
        initializedFromHost = true

        currentFontFamily = host.getDisplayFontFamily()
        currentFontSize = host.getDisplayFontSize()
        currentTheme = host.getDisplayTheme()
        currentHighlightColor = host.getHighlightColor()

        if (onPreferencesChanged == null) onPreferencesChanged = { ff, fs, t -> host.onDisplayPreferencesChanged(ff, fs, t) }
        if (onHighlightColorChanged == null) onHighlightColorChanged = { host.onHighlightColorChanged(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (savedInstanceState != null) restoreFromHost()
        return inflater.inflate(R.layout.fragment_display_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tvFontSize = view.findViewById<TextView>(R.id.tv_font_size)
        val btnDecrease = view.findViewById<ImageButton>(R.id.btn_font_decrease)
        val btnIncrease = view.findViewById<ImageButton>(R.id.btn_font_increase)
        val chipGroupFont = view.findViewById<ChipGroup>(R.id.chip_group_font)
        val chipGroupTheme = view.findViewById<ChipGroup>(R.id.chip_group_theme)

        updateFontSizeLabel(tvFontSize)

        val fontChipId = when (currentFontFamily) {
            FontFamily.SERIF -> R.id.chip_font_serif
            FontFamily.SANS_SERIF -> R.id.chip_font_sans
            FONT_CURSIVE -> R.id.chip_font_cursive
            FONT_MONOSPACE -> R.id.chip_font_monospace
            else -> R.id.chip_font_original
        }
        view.findViewById<Chip>(fontChipId).isChecked = true

        val themeChipId = when (currentTheme) {
            Theme.SEPIA -> R.id.chip_theme_sepia
            Theme.DARK -> R.id.chip_theme_dark
            else -> R.id.chip_theme_light
        }
        view.findViewById<Chip>(themeChipId).isChecked = true

        val chipGroupHighlight = view.findViewById<ChipGroup>(R.id.chip_group_highlight)
        val highlightChipId = when (currentHighlightColor) {
            "red" -> R.id.chip_highlight_red
            "yellow" -> R.id.chip_highlight_yellow
            else -> R.id.chip_highlight_blue
        }
        view.findViewById<Chip>(highlightChipId).isChecked = true

        chipGroupHighlight.setOnCheckedStateChangeListener { _, checkedIds ->
            currentHighlightColor = when {
                checkedIds.contains(R.id.chip_highlight_red) -> "red"
                checkedIds.contains(R.id.chip_highlight_yellow) -> "yellow"
                else -> "blue"
            }
            onHighlightColorChanged?.invoke(currentHighlightColor)
        }

        btnDecrease.setOnClickListener {
            if (currentFontSize > 0.5) {
                currentFontSize = (currentFontSize - 0.1).coerceAtLeast(0.5)
                updateFontSizeLabel(tvFontSize)
                notifyChanged()
            }
        }

        btnIncrease.setOnClickListener {
            if (currentFontSize < 3.0) {
                currentFontSize = (currentFontSize + 0.1).coerceAtMost(3.0)
                updateFontSizeLabel(tvFontSize)
                notifyChanged()
            }
        }

        chipGroupFont.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFontFamily = when {
                checkedIds.contains(R.id.chip_font_serif) -> FontFamily.SERIF
                checkedIds.contains(R.id.chip_font_sans) -> FontFamily.SANS_SERIF
                checkedIds.contains(R.id.chip_font_cursive) -> FONT_CURSIVE
                checkedIds.contains(R.id.chip_font_monospace) -> FONT_MONOSPACE
                else -> null
            }
            notifyChanged()
        }

        chipGroupTheme.setOnCheckedStateChangeListener { _, checkedIds ->
            currentTheme = when {
                checkedIds.contains(R.id.chip_theme_sepia) -> Theme.SEPIA
                checkedIds.contains(R.id.chip_theme_dark) -> Theme.DARK
                else -> Theme.LIGHT
            }
            notifyChanged()
        }
    }

    private fun updateFontSizeLabel(tv: TextView) {
        tv.text = "${(currentFontSize * 100).roundToInt()}%"
    }

    private fun notifyChanged() {
        onPreferencesChanged?.invoke(currentFontFamily, currentFontSize, currentTheme)
    }
}
