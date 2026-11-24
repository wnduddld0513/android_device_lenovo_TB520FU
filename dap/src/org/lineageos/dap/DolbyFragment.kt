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
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.android.settingslib.widget.ButtonPreference
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SelectorWithWidgetPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.android.settingslib.widget.SliderPreference

class DolbyFragment :
    SettingsBasePreferenceFragment(),
    Preference.OnPreferenceChangeListener,
    Preference.OnPreferenceClickListener,
    EqualizerView.Listener {

    private lateinit var switchBar: MainSwitchPreference
    private lateinit var dialogueCategory: PreferenceCategory
    private lateinit var dialogueEnhancer: SliderPreference
    private lateinit var ieqCategory: PreferenceCategory
    private lateinit var ieqNote: Preference
    private lateinit var geqCategory: PreferenceCategory
    private lateinit var equalizer: EqualizerPreference
    private lateinit var geqReset: ButtonPreference
    private lateinit var audioManager: AudioManager
    private var updating = false

    // The Quick Settings tile switches the effect and the profile while the screen is open.
    private val stateObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = updateState()
        }

    // The tuning applies the equalizer on the built-in speaker and the presets everywhere else;
    // follow where media plays.
    private val routeListener =
        AudioManager.OnDevicesForAttributesChangedListener { _, _ -> updateState() }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.dolby_settings, rootKey)
        audioManager = requireContext().getSystemService(AudioManager::class.java)!!

        switchBar = findPreference(PREF_DOLBY_ENABLE)!!
        switchBar.onPreferenceChangeListener = this

        for (key in MODE_KEYS + PRESET_KEYS) {
            findPreference<SelectorWithWidgetPreference>(key)!!.onPreferenceClickListener = this
        }

        dialogueCategory = findPreference(PREF_DIALOGUE_CATEGORY)!!
        dialogueEnhancer = findPreference(PREF_DIALOGUE_ENHANCER)!!
        dialogueEnhancer.min = 0
        dialogueEnhancer.max = DolbyCore.DIALOG_ENHANCER_MAX
        dialogueEnhancer.onPreferenceChangeListener = this

        ieqCategory = findPreference(PREF_IEQ_CATEGORY)!!
        ieqNote = findPreference(PREF_IEQ_NOTE)!!
        geqCategory = findPreference(PREF_GEQ_CATEGORY)!!
        equalizer = findPreference(PREF_GEQ)!!
        equalizer.listener = this
        geqReset = findPreference(PREF_GEQ_RESET)!!
        geqReset.setOnClickListener { resetEqualizer() }

        updateState()
    }

    override fun onStart() {
        super.onStart()
        val resolver = requireContext().contentResolver
        for (key in arrayOf(DolbyCore.SETTING_STATE, DolbyCore.SETTING_PROFILE)) {
            resolver.registerContentObserver(Settings.Global.getUriFor(key), false, stateObserver)
        }
        audioManager.addOnDevicesForAttributesChangedListener(
            MEDIA,
            requireContext().mainExecutor,
            routeListener,
        )
        updateState()
    }

    override fun onStop() {
        audioManager.removeOnDevicesForAttributesChangedListener(routeListener)
        requireContext().contentResolver.unregisterContentObserver(stateObserver)
        super.onStop()
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        if (updating) {
            return true
        }
        when (preference.key) {
            PREF_DOLBY_ENABLE -> {
                DolbyCore.setEnabled(newValue as Boolean, preference.context)
                updateState()
            }
            PREF_DIALOGUE_ENHANCER -> DolbyCore.setDialogEnhancerLevel(newValue as Int)
        }
        return true
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        val key = preference.key ?: return false
        val mode = MODE_KEYS.indexOf(key)
        if (mode >= 0) {
            DolbyCore.setMode(MODE_VALUES[mode], requireContext())
            updateState()
            return true
        }
        val preset = PRESET_KEYS.indexOf(key)
        if (preset >= 0) {
            DolbyCore.setIeqPreset(preset)
            updateState()
            return true
        }
        return false
    }

    override fun onBandsChanged(bands: FloatArray) =
        DolbyCore.setGeqBands(requireContext(), bands, persist = false)

    override fun onBandsCommitted(bands: FloatArray) =
        DolbyCore.setGeqBands(requireContext(), bands, persist = true)

    private fun resetEqualizer() {
        val flat = FloatArray(DolbyCore.GEQ_BANDS)
        DolbyCore.setGeqBands(requireContext(), flat, persist = true)
        equalizer.setBands(flat)
    }

    private fun isMediaOnSpeaker(): Boolean {
        val devices = audioManager.getAudioDevicesForAttributes(MEDIA)
        return devices.isEmpty() || devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    // The switch turns the effect on and off and leaves its profile alone, so the modes and the
    // settings of the selected one stay on screen, greyed out while the effect is off.
    private fun updateState() {
        val context = context ?: return
        val enabled = DolbyCore.isEnabled()
        val profile = DolbyCore.getSelectedProfile(context)
        updating = true

        switchBar.isChecked = enabled
        for ((index, key) in MODE_KEYS.withIndex()) {
            findPreference<SelectorWithWidgetPreference>(key)?.apply {
                isEnabled = enabled
                isChecked = profile == MODE_VALUES[index]
            }
        }

        // The dialogue enhancer belongs to the movie profile, the presets and the equalizer to
        // the music profile; the dynamic profile tunes itself.
        val movie = profile == DolbyCore.MODE_MOVIE
        val music = profile == DolbyCore.MODE_MUSIC

        dialogueCategory.isVisible = movie
        if (movie) {
            dialogueEnhancer.isEnabled = enabled
            dialogueEnhancer.value = DolbyCore.getDialogEnhancerLevel()
        }

        ieqCategory.isVisible = music
        geqCategory.isVisible = music
        if (music) {
            val speaker = isMediaOnSpeaker()

            val preset = DolbyCore.getIeqPreset()
            ieqNote.isVisible = speaker
            for ((index, key) in PRESET_KEYS.withIndex()) {
                findPreference<SelectorWithWidgetPreference>(key)?.apply {
                    isEnabled = enabled && !speaker
                    isChecked = index == preset
                }
            }

            equalizer.isEnabled = enabled && speaker
            equalizer.summary = if (speaker) null else getString(R.string.dolby_geq_speaker_only)
            equalizer.setBands(DolbyCore.getGeqBands(context))
            geqReset.isEnabled = enabled && speaker
        }

        updating = false
    }

    companion object {
        const val PREF_DOLBY_ENABLE = "dolby_enable"
        const val PREF_DIALOGUE_CATEGORY = "dolby_dialogue_category"
        const val PREF_DIALOGUE_ENHANCER = "dolby_dialogue_enhancer"
        const val PREF_IEQ_CATEGORY = "dolby_ieq_category"
        const val PREF_IEQ_NOTE = "dolby_ieq_note"
        const val PREF_GEQ_CATEGORY = "dolby_geq_category"
        const val PREF_GEQ = "dolby_geq"
        const val PREF_GEQ_RESET = "dolby_geq_reset"

        private val MEDIA = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()

        // Order matters: the index is the profile id of dax-default.xml.
        val MODE_KEYS = arrayOf("dolby_mode_dynamic", "dolby_mode_movie", "dolby_mode_music")

        val MODE_VALUES =
            intArrayOf(DolbyCore.MODE_DYNAMIC, DolbyCore.MODE_MOVIE, DolbyCore.MODE_MUSIC)

        // Order matters: the index is the IEQ preset id (none, detailed, balanced, warm).
        val PRESET_KEYS =
            arrayOf(
                "dolby_preset_none",
                "dolby_preset_detailed",
                "dolby_preset_balanced",
                "dolby_preset_warm",
            )

        fun modeTitle(context: Context, mode: Int): CharSequence =
            when (mode) {
                DolbyCore.MODE_MOVIE -> context.getString(R.string.dolby_profile_movie)
                DolbyCore.MODE_MUSIC -> context.getString(R.string.dolby_profile_music)
                DolbyCore.MODE_DYNAMIC -> context.getString(R.string.dolby_profile_dynamic)
                else -> context.getString(R.string.dolby_preset_none)
            }
    }
}
