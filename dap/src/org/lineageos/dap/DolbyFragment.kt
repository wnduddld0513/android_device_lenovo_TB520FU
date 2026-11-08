/*
 * Copyright (C) 2022-2025 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.dap

import android.content.Context
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SelectorWithWidgetPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment

class DolbyFragment :
    SettingsBasePreferenceFragment(),
    Preference.OnPreferenceChangeListener,
    Preference.OnPreferenceClickListener {

    private lateinit var switchBar: MainSwitchPreference
    private lateinit var dialogEnhancer: SwitchPreferenceCompat
    private lateinit var dialogAmount: SeekBarPreference
    private var updating = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.dolby_settings, rootKey)

        switchBar = findPreference(PREF_DOLBY_ENABLE)!!
        switchBar.onPreferenceChangeListener = this
        switchBar.isChecked = DolbyCore.isEnabled()

        for (key in MODE_KEYS) {
            findPreference<SelectorWithWidgetPreference>(key)?.onPreferenceClickListener = this
        }
        for (key in PRESET_KEYS) {
            findPreference<SelectorWithWidgetPreference>(key)?.onPreferenceClickListener = this
        }

        dialogEnhancer = findPreference(PREF_DIALOG_ENHANCER)!!
        dialogEnhancer.onPreferenceChangeListener = this
        dialogAmount =
            createSeekBar(
                PREF_DIALOG_AMOUNT,
                null,
                0,
                DolbyCore.DIALOG_ENHANCER_MAX,
            )
        dialogEnhancer.parent?.addPreference(dialogAmount)

        buildGraphicEqualizer()
        updateState()
    }

    private fun createSeekBar(
        key: String,
        title: CharSequence?,
        min: Int,
        max: Int,
    ): SeekBarPreference =
        SeekBarPreference(requireContext()).apply {
            this.key = key
            this.title = title
            setMin(min)
            setMax(max)
            setShowSeekBarValue(true)
            isPersistent = false
            onPreferenceChangeListener = this@DolbyFragment
        }

    // GeqView in ZuiSettings draws 10 bars (X_TITLES 47, 234, 469, 844, 1K, 2K, 3K, 5K, 9K, 13K)
    // for the 20 GraphicEqualizerBandGains entries, each between -6 and +6 dB.
    private fun buildGraphicEqualizer() {
        val screen = findPreference<PreferenceScreen>(PREF_GEQ) ?: return
        for (bar in 0 until DolbyCore.GEQ_BARS) {
            screen.addPreference(
                createSeekBar(
                    "$PREF_GEQ_BAR$bar",
                    GEQ_BAR_TITLES[bar],
                    DolbyCore.GEQ_MIN_DB,
                    DolbyCore.GEQ_MAX_DB,
                )
            )
        }
        screen.addPreference(
            Preference(requireContext()).apply {
                key = PREF_GEQ_RESET
                setTitle(R.string.dolby_geq_reset)
                onPreferenceClickListener = this@DolbyFragment
            }
        )
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        if (updating) {
            return true
        }
        when (preference.key) {
            PREF_DOLBY_ENABLE -> DolbyCore.setEnabled(newValue as Boolean)
            PREF_DIALOG_ENHANCER -> DolbyCore.setDialogEnhancerEnabled(newValue as Boolean)
            PREF_DIALOG_AMOUNT -> DolbyCore.setDialogEnhancerAmount(newValue as Int)
            else -> {
                if (preference.key?.startsWith(PREF_GEQ_BAR) == true) {
                    writeGeqBars()
                }
            }
        }
        preference.context?.let { DolbyCore.persist(it) }
        updateState()
        return true
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        val key = preference.key ?: return false
        val modeIndex = MODE_KEYS.indexOf(key)
        if (modeIndex >= 0) {
            DolbyCore.setMode(MODE_VALUES[modeIndex], requireContext())
            updateState()
            return true
        }
        val preset = PRESET_KEYS.indexOf(key)
        if (preset >= 0) {
            DolbyCore.setIeqPreset(preset)
            updateState()
            return true
        }
        if (key == PREF_GEQ_RESET) {
            DolbyCore.setGeqBars(IntArray(DolbyCore.GEQ_BARS))
            updateState()
            requireContext().let { DolbyCore.persist(it) }
            return true
        }
        return false
    }

    private fun writeGeqBars() {
        val screen = findPreference<PreferenceScreen>(PREF_GEQ) ?: return
        val bars = IntArray(DolbyCore.GEQ_BARS) { bar ->
            screen.findPreference<SeekBarPreference>("$PREF_GEQ_BAR$bar")?.value ?: 0
        }
        DolbyCore.setGeqBars(bars)
    }

    private fun updateState() {
        val mode = DolbyCore.getMode()
        updating = true

        switchBar.isChecked = mode != DolbyCore.MODE_OFF
        for ((index, key) in MODE_KEYS.withIndex()) {
            findPreference<SelectorWithWidgetPreference>(key)?.isChecked = mode == MODE_VALUES[index]
        }

        // The stock screen shows the dialogue enhancer for the movie profile
        // (DolbyVideoEnhancerPreferenceController.shouldVisible) and the music presets plus the
        // equalizer for the music profile (DolbyMusicEffectPreferenceController.shouldVisible,
        // DolbyMusicGeqPreferenceController.shouldVisible).
        val movie = mode == DolbyCore.MODE_MOVIE
        val music = mode == DolbyCore.MODE_MUSIC

        dialogEnhancer.isVisible = movie
        dialogAmount.isVisible = movie
        dialogEnhancer.isChecked = DolbyCore.isDialogEnhancerEnabled()
        dialogAmount.value = DolbyCore.getDialogEnhancerAmount()

        val preset = DolbyCore.getIeqPreset()
        for ((index, key) in PRESET_KEYS.withIndex()) {
            findPreference<SelectorWithWidgetPreference>(key)?.apply {
                isVisible = music
                isChecked = index == preset
            }
        }

        val screen = findPreference<PreferenceScreen>(PREF_GEQ)
        screen?.isVisible = music
        val bars = DolbyCore.getGeqBars()
        for (bar in 0 until DolbyCore.GEQ_BARS) {
            screen?.findPreference<SeekBarPreference>("$PREF_GEQ_BAR$bar")?.value = bars[bar]
        }
        updating = false
    }

    companion object {
        const val PREF_DOLBY_ENABLE = "dolby_enable"
        const val PREF_DIALOG_ENHANCER = "dolby_dialogue_enhancer"
        const val PREF_DIALOG_AMOUNT = "dolby_dialogue_enhancer_amount"
        const val PREF_GEQ = "dolby_geq"
        const val PREF_GEQ_BAR = "dolby_geq_band"
        const val PREF_GEQ_RESET = "dolby_geq_reset"

        // Order matters: the index is the stock mode value (AI_DOLBY_MODE 0, VIDEO_DOLBY_MODE 1,
        // MUSIC_DOLBY_MODE 2) and DolbyCore.MODE_OFF for the first entry.
        val MODE_KEYS =
            arrayOf("dolby_mode_off", "dolby_mode_dynamic", "dolby_mode_movie", "dolby_mode_music")

        val MODE_VALUES =
            intArrayOf(
                DolbyCore.MODE_OFF,
                DolbyCore.MODE_DYNAMIC,
                DolbyCore.MODE_MOVIE,
                DolbyCore.MODE_MUSIC,
            )

        // DolbyMusicEffectPreference radio buttons: none, detailed, balanced, warm.
        val PRESET_KEYS =
            arrayOf(
                "dolby_preset_none",
                "dolby_preset_detailed",
                "dolby_preset_balanced",
                "dolby_preset_warm",
            )

        // GeqView.X_TITLES in ZuiSettings.
        private val GEQ_BAR_TITLES =
            arrayOf("47", "234", "469", "844", "1K", "2K", "3K", "5K", "9K", "13K")

        fun modeTitle(context: Context, mode: Int): CharSequence =
            when (mode) {
                DolbyCore.MODE_MOVIE -> context.getString(R.string.dolby_profile_movie)
                DolbyCore.MODE_MUSIC -> context.getString(R.string.dolby_profile_music)
                DolbyCore.MODE_DYNAMIC -> context.getString(R.string.dolby_profile_dynamic)
                else -> context.getString(R.string.dolby_preset_none)
            }
    }
}
