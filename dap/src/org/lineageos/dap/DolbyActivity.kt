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

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import com.android.settingslib.activityembedding.ActivityEmbeddingUtils
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity

class DolbyActivity : CollapsingToolbarBaseActivity() {
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (openInEmbeddedSettings()) {
            return
        }
        supportFragmentManager
            .beginTransaction()
            .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame, DolbyFragment())
            .commit()
    }

    // On a two-pane Settings (the Quick Settings tile long-press and any other external launch
    // land here) the screen belongs in the right pane, the way the Wi-Fi tile opens Wi-Fi.
    // Settings starts us again inside its task with ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY,
    // and the second instance is embedded, so it does not redirect again.
    private fun openInEmbeddedSettings(): Boolean {
        if (!ActivityEmbeddingUtils.isEmbeddingActivityEnabled(this)) {
            return false
        }
        if (ActivityEmbeddingUtils.isActivityEmbedded(this)) {
            return false
        }

        val page = Intent().setComponent(ComponentName(this, DolbyActivity::class.java))
        val deepLink =
            Intent(Settings.ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY)
                .putExtra(
                    Settings.EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_INTENT_URI,
                    page.toUri(Intent.URI_INTENT_SCHEME),
                )
                .putExtra(
                    Settings.EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_HIGHLIGHT_MENU_KEY,
                    HIGHLIGHT_MENU_KEY,
                )
        runCatching { startActivity(deepLink) }.onFailure { return false }
        finish()
        return true
    }

    private companion object {
        // packages/apps/Settings/res/values/menu_keys.xml: menu_key_sound
        const val HIGHLIGHT_MENU_KEY = "top_level_sound"
    }
}
