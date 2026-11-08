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
import android.media.audiofx.AudioEffect
import android.provider.Settings
import android.util.Log
import java.util.UUID

object DolbyCore {
    private const val TAG = "DolbyCore"

    // Effect descriptor at offset 128116 of /vendor/lib64/soundfx/libswdap.so: the type uuid
    // comes first, then the instance uuid that audio_effects.xml declares for
    // <effect name="dap">. AudioFlinger looks the effect up by the pair.
    private val EFFECT_TYPE_DAP = UUID.fromString("46d279d9-9be7-453d-9d7c-ef937f675587")
    private val EFFECT_UUID_DAP = UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa")

    // Parameter protocol of com.dolby.dax.DolbyAudioEffect (daxService.apk):
    //   setIntParam/setBoolParam/setDapParameter build {id, count, values...} and send it
    //   with EFFECT_CMD_SET_PARAM on parameter 5;
    //   getIntParam/getBoolParam repeat the id and read parameter "id + 5";
    //   getDapParameter builds the parameter from
    //   EFFECT_PARAM_PROFILE_PARAMETER + 5 + (id << 16) + (profile << 8).
    private const val PARAM_DAP = 5
    private const val PARAM_ON_OFF = 0 // DsParams.EFFECT_PARAM_EFFECT_ENABLE
    private const val PARAM_PROFILE = 0x0A000000 // DsParams.EFFECT_PARAM_PROFILE
    private const val PARAM_PROFILE_NUM = 0x03000000 // DsParams.EFFECT_PARAM_PROFILE_NUM
    private const val PARAM_PROFILE_PARAMETER = 0x01000000 // DsParams.EFFECT_PARAM_PROFILE_PARAMETER
    private const val PARAM_PROFILE_READ = PARAM_PROFILE_PARAMETER + PARAM_DAP

    // Sub-parameter ids of the profile parameters, from DsParams (daxService.apk).
    private const val ID_IEQ_PRESET = 104 // DsParams.IntelligentEqualizerPreset
    private const val ID_DIALOG_ENHANCER_ENABLE = 105 // DsParams.DialogEnhancementEnable
    private const val ID_DIALOG_ENHANCER_AMOUNT = 108 // DsParams.DialogEnhancementAmount
    private const val ID_GEQ_GAINS = 110 // DsParams.GraphicEqualizerBandGains

    // DolbyEffectController.PROFILE_DYNAMIC / _MOVIE / _MUSIC; "Off" has no profile, it disables
    // the effect (DaxService.setDsOn / DolbyEffectController.setState).
    const val MODE_OFF = -1
    const val MODE_DYNAMIC = 0
    const val MODE_MOVIE = 1
    const val MODE_MUSIC = 2

    // dax-default.xml ieq presets: 1 detailed, 2 balanced, 3 warm; 0 leaves the tuning alone.
    const val PRESET_NONE = 0
    const val PRESET_DETAILED = 1
    const val PRESET_BALANCED = 2
    const val PRESET_WARM = 3

    // com.dolby.dax.DolbyAudioEffect.setDialogEnhancerAmount accepts 0..16.
    const val DIALOG_ENHANCER_MAX = 16

    // GeqView draws 10 bars for the 20 GraphicEqualizerBandGains entries (bar i is gain 2i),
    // with MIN_GAIN -6 / MAX_GAIN +6 dB and gains carried in 1/16 dB steps (GeqView divides by
    // 16.0 and clamps to [-6, 6]).
    const val GEQ_BARS = 10
    const val GEQ_GAINS = 20
    const val GEQ_MIN_DB = -6
    const val GEQ_MAX_DB = 6
    private const val GEQ_GAIN_SCALE = 16

    // Keys and session of the stock stack: Settings.Global "dolby_dap_profile" / "dlb_dap_state"
    // (DolbyModePreferenceController.DOLBY_DAP_PROFILE, DolbyAtmosUtils.DLB_DAP_STATE) and the
    // global output mix session (DaxService.initDolbyHalClient creates DolbyAudioEffect(-1, 0)).
    private const val SETTING_PROFILE = "dolby_dap_profile"
    private const val SETTING_STATE = "dlb_dap_state"
    private const val SETTING_GEQ = "dolby_geq_values"
    private const val GLOBAL_SESSION = 0

    private var audioEffect: AudioEffect? = null

    private fun getEffect(): AudioEffect? {
        val current = audioEffect
        if (current != null && runCatching { current.hasControl() }.getOrDefault(false)) {
            return current
        }

        // Create on demand: audioserver is not necessarily up when the boot receiver runs, and
        // it drops the effect when it restarts (the stock service reconnects for the same reason).
        runCatching { current?.release() }
        audioEffect =
            runCatching { AudioEffect(EFFECT_TYPE_DAP, EFFECT_UUID_DAP, 0, GLOBAL_SESSION) }
                .onFailure { Log.e(TAG, "Failed to create the DAP effect: $it") }
                .getOrNull()
        return audioEffect
    }

    private fun intToByteArray(value: Int, buffer: ByteArray, offset: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun byteArrayToInt(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 0xFF) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeDapParam(values: IntArray) {
        val effect = getEffect() ?: return
        val buffer = ByteArray(values.size * 4)
        for ((index, value) in values.withIndex()) {
            intToByteArray(value, buffer, index * 4)
        }
        val status = runCatching { effect.setParameter(PARAM_DAP, buffer) }.getOrNull()
        if (status != AudioEffect.SUCCESS) {
            Log.w(TAG, "Failed to write DAP parameter ${values.firstOrNull()}: $status")
        }
    }

    private fun readDapParam(parameter: Int, count: Int): IntArray? {
        val effect = getEffect() ?: return null
        val buffer = ByteArray(count * 4)
        val status = runCatching { effect.getParameter(parameter, buffer) }.getOrNull()
        if (status != AudioEffect.SUCCESS) {
            Log.w(TAG, "Failed to read DAP parameter $parameter: $status")
            return null
        }
        return IntArray(count) { byteArrayToInt(buffer, it * 4) }
    }

    private fun setProfileParam(id: Int, values: IntArray) {
        val profile = getProfile()
        if (profile < 0) {
            return
        }
        writeDapParam(
            intArrayOf(PARAM_PROFILE_PARAMETER, values.size + 1, profile, id) + values
        )
    }

    private fun getProfileParam(id: Int, count: Int): IntArray? {
        val profile = getProfile()
        if (profile < 0) {
            return null
        }
        return readDapParam(
            PARAM_PROFILE_READ + (id shl 16) + (profile shl 8),
            count,
        )
    }

    fun getProfile(): Int = readDapParam(PARAM_PROFILE + PARAM_DAP, 1)?.get(0) ?: -1

    fun setProfile(profile: Int) {
        val numProfiles = readDapParam(PARAM_PROFILE_NUM + PARAM_DAP, 1)?.get(0) ?: 0
        if (numProfiles > 0 && (profile < 0 || profile >= numProfiles)) {
            Log.e(TAG, "Invalid profile index $profile")
            return
        }
        writeDapParam(intArrayOf(PARAM_PROFILE, 1, profile))
    }

    fun isEnabled(): Boolean = readDapParam(PARAM_ON_OFF + PARAM_DAP, 1)?.get(0)?.let { it > 0 } ?: false

    fun setEnabled(enabled: Boolean) {
        writeDapParam(intArrayOf(PARAM_ON_OFF, 1, if (enabled) 1 else 0))
        val effect = getEffect() ?: return
        runCatching { effect.enabled = enabled }
    }

    fun getMode(): Int = if (!isEnabled()) MODE_OFF else getProfile()

    fun getLastProfile(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, SETTING_PROFILE, MODE_DYNAMIC)
            .takeIf { it != MODE_OFF } ?: MODE_DYNAMIC

    fun setMode(mode: Int, context: Context?) {
        if (mode == MODE_OFF) {
            setEnabled(false)
        } else {
            setProfile(mode)
            if (!isEnabled()) {
                setEnabled(true)
            }
        }
        context?.let { persist(it) }
    }

    fun getIeqPreset(): Int = getProfileParam(ID_IEQ_PRESET, 1)?.get(0) ?: PRESET_NONE

    fun setIeqPreset(preset: Int) = setProfileParam(ID_IEQ_PRESET, intArrayOf(preset))

    fun isDialogEnhancerEnabled(): Boolean =
        getProfileParam(ID_DIALOG_ENHANCER_ENABLE, 1)?.get(0)?.let { it > 0 } ?: false

    fun setDialogEnhancerEnabled(enabled: Boolean) =
        setProfileParam(ID_DIALOG_ENHANCER_ENABLE, intArrayOf(if (enabled) 1 else 0))

    fun getDialogEnhancerAmount(): Int =
        getProfileParam(ID_DIALOG_ENHANCER_AMOUNT, 1)?.get(0) ?: 0

    fun setDialogEnhancerAmount(amount: Int) {
        val value = amount.coerceIn(0, DIALOG_ENHANCER_MAX)
        if (value != getDialogEnhancerAmount()) {
            setProfileParam(ID_DIALOG_ENHANCER_AMOUNT, intArrayOf(value))
        }
    }

    fun getGeqBars(): IntArray {
        val gains = getProfileParam(ID_GEQ_GAINS, GEQ_GAINS) ?: IntArray(GEQ_GAINS)
        return IntArray(GEQ_BARS) { bar ->
            (gains[bar * 2] / GEQ_GAIN_SCALE).coerceIn(GEQ_MIN_DB, GEQ_MAX_DB)
        }
    }

    fun setGeqBars(barsDb: IntArray) {
        val gains = IntArray(GEQ_GAINS)
        for (bar in 0 until GEQ_BARS) {
            val db = (barsDb.getOrNull(bar) ?: 0).coerceIn(GEQ_MIN_DB, GEQ_MAX_DB)
            gains[bar * 2] = db * GEQ_GAIN_SCALE
        }
        // GeqView.updateUserGainForBar keeps the odd entries at the mean of the two neighbouring
        // bars, so the 20 gains of the effect stay a smooth 10-bar curve.
        for (bar in 0 until GEQ_BARS - 1) {
            gains[bar * 2 + 1] = (gains[bar * 2] + gains[(bar + 1) * 2]) / 2
        }
        gains[GEQ_GAINS - 1] = gains[GEQ_GAINS - 2]
        setProfileParam(ID_GEQ_GAINS, gains)
    }

    fun persist(context: Context) {
        val resolver = context.contentResolver
        Settings.Global.putInt(resolver, SETTING_PROFILE, getMode())
        Settings.Global.putInt(resolver, SETTING_STATE, if (isEnabled()) 1 else 0)
        Settings.Global.putString(
            resolver,
            SETTING_GEQ,
            getGeqBars().joinToString(","),
        )
    }

    fun restore(context: Context) {
        val resolver = context.contentResolver
        val mode = Settings.Global.getInt(resolver, SETTING_PROFILE, MODE_DYNAMIC)
        if (mode == MODE_OFF) {
            setEnabled(false)
        } else {
            setProfile(mode)
            setEnabled(true)
        }
        Settings.Global.getString(resolver, SETTING_GEQ)
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == GEQ_BARS }
            ?.let { setGeqBars(it.toIntArray()) }
    }
}
