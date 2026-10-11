package com.aicode.feature.vnc.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.vncOverlayDataStore by preferencesDataStore(
    name = "vnc_overlay_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/** VNC 悬浮小窗的记忆状态：位置 / 尺寸 / 钉住，以及上次手动收起时间（自动弹出冷却用）。 */
data class VncOverlayPrefs(
    val offsetX: Float,
    val offsetY: Float,
    val widthDp: Float,
    val heightDp: Float,
    val pinned: Boolean,
    val dismissedAtMs: Long,
)

@Singleton
class VncOverlayPrefsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val OFFSET_X = floatPreferencesKey("vnc_offset_x")
        val OFFSET_Y = floatPreferencesKey("vnc_offset_y")
        val WIDTH_DP = floatPreferencesKey("vnc_width_dp")
        val HEIGHT_DP = floatPreferencesKey("vnc_height_dp")
        val PINNED = booleanPreferencesKey("vnc_pinned")
        val DISMISSED_AT = longPreferencesKey("vnc_dismissed_at")
    }

    val prefs: Flow<VncOverlayPrefs> = context.vncOverlayDataStore.data.map { p ->
        VncOverlayPrefs(
            offsetX = p[Keys.OFFSET_X] ?: Float.MIN_VALUE,
            offsetY = p[Keys.OFFSET_Y] ?: Float.MIN_VALUE,
            widthDp = p[Keys.WIDTH_DP] ?: 0f,
            heightDp = p[Keys.HEIGHT_DP] ?: 0f,
            pinned = p[Keys.PINNED] ?: false,
            dismissedAtMs = p[Keys.DISMISSED_AT] ?: 0L
        )
    }

    suspend fun saveGeometry(offsetX: Float, offsetY: Float, widthDp: Float, heightDp: Float) {
        context.vncOverlayDataStore.edit {
            it[Keys.OFFSET_X] = offsetX
            it[Keys.OFFSET_Y] = offsetY
            it[Keys.WIDTH_DP] = widthDp
            it[Keys.HEIGHT_DP] = heightDp
        }
    }

    suspend fun savePinned(pinned: Boolean) {
        context.vncOverlayDataStore.edit { it[Keys.PINNED] = pinned }
    }

    suspend fun saveDismissedAt(atMs: Long) {
        context.vncOverlayDataStore.edit { it[Keys.DISMISSED_AT] = atMs }
    }
}
