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

    // The "dap" effect of audio_effects.xml. AudioFlinger picks the effect by this uuid alone, so
    // the type stays EFFECT_TYPE_NULL and no type uuid is passed.
    private val EFFECT_UUID_DAP = UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa")

    // Control of an effect goes to the client with the highest priority: normal is 0, negative is
    // below normal. The handle kept for the whole boot sits below normal so it never takes control
    // away from anybody, and the writes that need control are done through a short-lived handle
    // above normal, which every other client yields to.
    private const val EFFECT_PRIORITY = -1
    private const val WRITE_PRIORITY = 1
    private const val GLOBAL_SESSION = 0

    // Parameter protocol of the DAP effect:
    //   a write builds {id, count, values...} and sends it with EFFECT_CMD_SET_PARAM on 5;
    //   a read repeats the id and reads parameter "id + 5", whose answer carries the values
    //   first and two more ints after them;
    //   a profile parameter is read from
    //   EFFECT_PARAM_PROFILE_PARAMETER + 5 + (id << 16) + (profile << 8).
    private const val PARAM_DAP = 5
    private const val PARAM_ON_OFF = 0 // DsParams.EFFECT_PARAM_EFFECT_ENABLE
    private const val PARAM_PROFILE = 0x0A000000 // DsParams.EFFECT_PARAM_PROFILE
    private const val PARAM_PROFILE_NUM = 0x03000000 // DsParams.EFFECT_PARAM_PROFILE_NUM
    private const val PARAM_PROFILE_PARAMETER = 0x01000000 // DsParams.EFFECT_PARAM_PROFILE_PARAMETER
    private const val PARAM_PROFILE_READ = PARAM_PROFILE_PARAMETER + PARAM_DAP
    private const val READ_TAIL_INTS = 2

    // Sub-parameter ids of the profile parameters, from DsParams.
    private const val ID_IEQ_PRESET = 104 // DsParams.IntelligentEqualizerPreset
    private const val ID_DIALOG_ENHANCER_ENABLE = 105 // DsParams.DialogEnhancementEnable
    private const val ID_DIALOG_ENHANCER_AMOUNT = 108 // DsParams.DialogEnhancementAmount
    private const val ID_GEQ_GAINS = 110 // DsParams.GraphicEqualizerBandGains

    // Profile ids of dax-default.xml: Dynamic, Movie, Music. "Off" has no profile, it disables
    // the effect.
    const val MODE_OFF = -1
    const val MODE_DYNAMIC = 0
    const val MODE_MOVIE = 1
    const val MODE_MUSIC = 2

    // IEQ presets of dax-default.xml: 1 detailed, 2 balanced, 3 warm; 0 leaves the tuning alone.
    const val PRESET_NONE = 0
    const val PRESET_DETAILED = 1
    const val PRESET_BALANCED = 2
    const val PRESET_WARM = 3

    // The dialogue enhancer level of the screen: 0 switches the enhancer off, 1 to 9 is its
    // amount (the effect itself accepts up to 16).
    const val DIALOG_ENHANCER_MAX = 9

    // The effect has 20 graphic equalizer gains in 1/16 dB. The screen shows 10 bands, the even
    // gains, each between -6 and +6 dB in 0.5 dB steps; every odd gain is the mean of its two
    // neighbouring bands and the last one repeats the last band.
    const val GEQ_BANDS = 10
    const val GEQ_MAX_DB = 6f
    const val GEQ_STEP_DB = 0.5f
    private const val GEQ_GAINS = 20
    private const val GEQ_GAIN_SCALE = 16

    // The profile and the switch state live in Settings.Global, the equalizer curve in
    // Settings.System as the 20 raw gains.
    const val SETTING_PROFILE = "dolby_dap_profile"
    const val SETTING_STATE = "dlb_dap_state"
    private const val SETTING_GEQ = "dolby_geq_values"

    private var audioEffect: AudioEffect? = null
    private var appContext: Context? = null
    private var restoring = false

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // The effect starts enabled on the Dynamic profile, and the profile, switch state and
    // equalizer curve that were saved last go back in.
    fun restore(context: Context) {
        init(context)
        applySettings(context)
    }

    private fun applySettings(context: Context) {
        val resolver = context.contentResolver
        val stored = Settings.Global.getInt(resolver, SETTING_PROFILE, MODE_DYNAMIC)
        // An older build stored "off" in the profile key; that is not a profile, it means the
        // effect is switched off and its profile is left alone.
        val legacyOff = stored == MODE_OFF
        val profile = if (stored in MODE_DYNAMIC..MODE_MUSIC) stored else MODE_DYNAMIC
        val enabled = !legacyOff && Settings.Global.getInt(resolver, SETTING_STATE, 1) != 0

        restoring = true
        try {
            // Write the curve and the profile before enabling, so the effect never plays with
            // the profile it happened to hold.
            getStoredGeqGains(context)?.let { setGeqGains(it) }
            if (!legacyOff) {
                setProfile(profile)
            }
            setEnabled(enabled, context)
        } finally {
            restoring = false
        }

        if (legacyOff) {
            // Move the stored values to the current meaning: switch off, profile kept.
            getProfile().takeIf { it >= 0 }?.let {
                Settings.Global.putInt(resolver, SETTING_PROFILE, it)
            }
        }
    }

    private fun getEffect(): AudioEffect? {
        audioEffect?.let { return it }

        // audioserver keeps no effect across a restart, so create on demand and put the saved
        // state into the new instance.
        val created = createEffect(EFFECT_PRIORITY)
        audioEffect = created
        if (created != null && !restoring) {
            appContext?.let { applySettings(it) }
        }
        return created
    }

    private fun createEffect(priority: Int): AudioEffect? =
        runCatching {
                AudioEffect(
                    AudioEffect.EFFECT_TYPE_NULL,
                    EFFECT_UUID_DAP,
                    priority,
                    GLOBAL_SESSION,
                )
            }
                .onFailure { Log.e(TAG, "Failed to create the DAP effect: $it") }
                .getOrNull()

    // A handle that fails or was released is dropped; the next call opens a new one. Only the
    // shared handle is forgotten here, a short-lived writer is released by its caller.
    private fun dropEffect(effect: AudioEffect) {
        if (audioEffect === effect) {
            audioEffect = null
        }
        runCatching { effect.release() }
    }

    private inline fun withWriter(block: (AudioEffect) -> Unit) {
        val writer = createEffect(WRITE_PRIORITY) ?: return
        try {
            block(writer)
        } finally {
            runCatching { writer.release() }
        }
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

    private fun buildParam(values: IntArray): ByteArray {
        val buffer = ByteArray(values.size * 4)
        for ((index, value) in values.withIndex()) {
            intToByteArray(value, buffer, index * 4)
        }
        return buffer
    }

    private fun tryWrite(effect: AudioEffect, values: IntArray): Boolean {
        val status =
            try {
                effect.setParameter(PARAM_DAP, buildParam(values))
            } catch (e: RuntimeException) {
                dropEffect(effect)
                Log.w(TAG, "Failed to write DAP parameter ${values.firstOrNull()}: $e")
                return false
            }
        if (status == AudioEffect.SUCCESS) {
            return true
        }
        if (status == AudioEffect.ERROR_INVALID_OPERATION) {
            // Not the handle in control; the caller retries with a short-lived one.
            Log.d(TAG, "No control over the DAP effect for parameter ${values.firstOrNull()}")
        } else {
            // Every write we make is valid for this effect, so any other answer means the handle
            // is gone; the next call opens a new one.
            dropEffect(effect)
            Log.w(TAG, "Failed to write DAP parameter ${values.firstOrNull()}: $status")
        }
        return false
    }

    private fun writeDapParam(values: IntArray) {
        val effect = getEffect()
        if (effect != null && tryWrite(effect, values)) {
            return
        }
        withWriter { tryWrite(it, values) }
    }

    private fun readDapParam(parameter: Int, count: Int): IntArray? {
        val effect = getEffect() ?: return null
        val buffer = ByteArray((count + READ_TAIL_INTS) * 4)
        // getParameter() returns the number of bytes the effect wrote, or a negative error.
        val size =
            try {
                effect.getParameter(parameter, buffer)
            } catch (e: RuntimeException) {
                dropEffect(effect)
                Log.w(TAG, "Failed to read DAP parameter $parameter: $e")
                return null
            }
        if (size < 0) {
            // Reads need no control, so a failure here is the handle, not the request.
            dropEffect(effect)
            Log.w(TAG, "Failed to read DAP parameter $parameter: $size")
            return null
        }
        if (size < count * 4) {
            Log.w(TAG, "Short read of DAP parameter $parameter: $size bytes")
            return null
        }
        return IntArray(count) { byteArrayToInt(buffer, it * 4) }
    }

    private fun setProfileParam(profile: Int, id: Int, values: IntArray) {
        writeDapParam(
            intArrayOf(PARAM_PROFILE_PARAMETER, values.size + 1, profile, id) + values
        )
    }

    private fun setProfileParam(id: Int, values: IntArray) {
        val profile = getProfile()
        if (profile >= 0) {
            setProfileParam(profile, id, values)
        }
    }

    private fun getProfileParam(profile: Int, id: Int, count: Int): IntArray? =
        readDapParam(
            PARAM_PROFILE_READ + (id shl 16) + (profile shl 8),
            count,
        )

    private fun getProfileParam(id: Int, count: Int): IntArray? {
        val profile = getProfile()
        return if (profile < 0) null else getProfileParam(profile, id, count)
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

    fun setEnabled(enabled: Boolean, context: Context?) {
        val value = intArrayOf(PARAM_ON_OFF, 1, if (enabled) 1 else 0)
        val effect = getEffect()
        val applied =
            effect != null && tryWrite(effect, value) && trySetEnabled(effect, enabled)
        if (!applied) {
            // The handle we hold is not in control; ask for it with a short-lived handle.
            withWriter { writer ->
                tryWrite(writer, value)
                if (!trySetEnabled(writer, enabled)) {
                    Log.w(TAG, "Failed to switch the DAP effect ${if (enabled) "on" else "off"}")
                }
            }
        }
        // Record what was asked for, not what the effect answers: a change made while the effect
        // is unreachable must survive.
        context?.let {
            Settings.Global.putInt(it.contentResolver, SETTING_STATE, if (enabled) 1 else 0)
        }
    }

    private fun trySetEnabled(effect: AudioEffect, enabled: Boolean): Boolean =
        try {
            effect.setEnabled(enabled)
            effect.getEnabled() == enabled
        } catch (e: RuntimeException) {
            dropEffect(effect)
            false
        }

    fun getMode(): Int = if (!isEnabled()) MODE_OFF else getProfile()

    // The screen and the tile only ever ask for the profiles they show (0..2), and the stored key
    // holds one of those; "off" is the switch state, never a profile.
    fun getLastProfile(context: Context): Int {
        val stored = Settings.Global.getInt(context.contentResolver, SETTING_PROFILE, MODE_DYNAMIC)
        return if (stored in MODE_DYNAMIC..MODE_MUSIC) stored else MODE_DYNAMIC
    }

    // The profile the effect holds, kept while it is switched off; the saved one when the effect
    // is unreachable or holds a profile the screen does not offer.
    fun getSelectedProfile(context: Context): Int =
        getProfile().takeIf { it in MODE_DYNAMIC..MODE_MUSIC } ?: getLastProfile(context)

    fun setMode(mode: Int, context: Context?) {
        if (mode == MODE_OFF) {
            setEnabled(false, context)
        } else {
            if (!isEnabled()) {
                setEnabled(true, context)
            }
            setProfile(mode)
            context?.let {
                Settings.Global.putInt(it.contentResolver, SETTING_PROFILE, mode)
            }
        }
    }

    fun getIeqPreset(): Int = getProfileParam(ID_IEQ_PRESET, 1)?.get(0) ?: PRESET_NONE

    fun setIeqPreset(preset: Int) = setProfileParam(ID_IEQ_PRESET, intArrayOf(preset))

    private fun isDialogEnhancerEnabled(): Boolean =
        getProfileParam(ID_DIALOG_ENHANCER_ENABLE, 1)?.get(0)?.let { it > 0 } ?: false

    private fun setDialogEnhancerEnabled(enabled: Boolean) =
        setProfileParam(ID_DIALOG_ENHANCER_ENABLE, intArrayOf(if (enabled) 1 else 0))

    private fun getDialogEnhancerAmount(): Int =
        getProfileParam(ID_DIALOG_ENHANCER_AMOUNT, 1)?.get(0) ?: 0

    // 0 while the enhancer is off, its amount while it is on.
    fun getDialogEnhancerLevel(): Int =
        if (isDialogEnhancerEnabled()) {
            getDialogEnhancerAmount().coerceIn(0, DIALOG_ENHANCER_MAX)
        } else {
            0
        }

    // Level 0 switches the enhancer off and leaves the amount of the tuning alone; any other
    // level switches it on with that amount.
    fun setDialogEnhancerLevel(level: Int) {
        val value = level.coerceIn(0, DIALOG_ENHANCER_MAX)
        val enabled = isDialogEnhancerEnabled()
        if (value == 0) {
            if (enabled) {
                setDialogEnhancerEnabled(false)
            }
            return
        }
        if (value != getDialogEnhancerAmount()) {
            setProfileParam(ID_DIALOG_ENHANCER_AMOUNT, intArrayOf(value))
        }
        if (!enabled) {
            setDialogEnhancerEnabled(true)
        }
    }

    // The equalizer curve belongs to the Music profile, whatever profile the effect holds when it
    // is read or written.
    private fun readGeqGains(): IntArray? = getProfileParam(MODE_MUSIC, ID_GEQ_GAINS, GEQ_GAINS)

    private fun setGeqGains(gains: IntArray) = setProfileParam(MODE_MUSIC, ID_GEQ_GAINS, gains)

    private fun getStoredGeqGains(context: Context): IntArray? =
        Settings.System.getString(context.contentResolver, SETTING_GEQ)
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == GEQ_GAINS }
            ?.toIntArray()

    // The bands in dB; the saved curve when the effect is unreachable, flat without either.
    fun getGeqBands(context: Context): FloatArray {
        val gains = readGeqGains() ?: getStoredGeqGains(context) ?: IntArray(GEQ_GAINS)
        return FloatArray(GEQ_BANDS) { band ->
            (gains[band * 2].toFloat() / GEQ_GAIN_SCALE).coerceIn(-GEQ_MAX_DB, GEQ_MAX_DB)
        }
    }

    // Writes the curve; with persist the same gains are saved for the next boot.
    fun setGeqBands(context: Context, bands: FloatArray, persist: Boolean) {
        val gains = geqGainsFromBands(bands)
        setGeqGains(gains)
        if (persist) {
            Settings.System.putString(context.contentResolver, SETTING_GEQ, gains.joinToString(","))
        }
    }

    private fun geqGainsFromBands(bands: FloatArray): IntArray {
        val gains = IntArray(GEQ_GAINS)
        for (band in 0 until GEQ_BANDS) {
            gains[band * 2] = Math.round(snapGeqDb(bands.getOrElse(band) { 0f }) * GEQ_GAIN_SCALE)
        }
        for (band in 0 until GEQ_BANDS - 1) {
            gains[band * 2 + 1] = (gains[band * 2] + gains[(band + 1) * 2]) / 2
        }
        gains[GEQ_GAINS - 1] = gains[GEQ_GAINS - 2]
        return gains
    }

    fun snapGeqDb(db: Float): Float =
        (Math.round(db / GEQ_STEP_DB) * GEQ_STEP_DB).coerceIn(-GEQ_MAX_DB, GEQ_MAX_DB)
}
