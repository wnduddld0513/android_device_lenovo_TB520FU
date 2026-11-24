/*
 * Copyright (C) 2026 The LineageOS Project
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
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

// Holds the EqualizerView in the preference list; the summary carries the note shown while the
// equalizer is unavailable.
class EqualizerPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    var listener: EqualizerView.Listener? = null

    private var bands = FloatArray(DolbyCore.GEQ_BANDS)
    private var view: EqualizerView? = null

    init {
        layoutResource = R.layout.dolby_equalizer
        isSelectable = false
        isPersistent = false
    }

    fun setBands(values: FloatArray) {
        bands = values.copyOf()
        view?.setBands(bands)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false
        view =
            (holder.findViewById(R.id.equalizer) as EqualizerView).also {
                it.listener =
                    object : EqualizerView.Listener {
                        override fun onBandsChanged(bands: FloatArray) {
                            this@EqualizerPreference.bands = bands
                            listener?.onBandsChanged(bands)
                        }

                        override fun onBandsCommitted(bands: FloatArray) {
                            this@EqualizerPreference.bands = bands
                            listener?.onBandsCommitted(bands)
                        }
                    }
                it.setBands(bands)
                it.isEnabled = isEnabled
            }
    }
}
