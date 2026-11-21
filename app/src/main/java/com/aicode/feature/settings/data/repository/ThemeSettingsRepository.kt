package com.aicode.feature.settings.data.repository

import android.content.Context
import android.content.res.Configuration
import com.aicode.R
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.themeDataStore by preferencesDataStore(
    name = "theme_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

enum class AppThemeMode(val labelRes: Int) {
    AUTO(R.string.theme_auto),
    DARK(R.string.theme_dark),
    BLACK(R.string.theme_black),
    LIGHT(R.string.theme_light),
    SCHEDULED(R.string.theme_scheduled);

    companion object {
        fun fromPersisted(value: String?): AppThemeMode? = entries.firstOrNull { it.name == value }
    }
}

/** 解析后的运行时主题：dark 驱动配色，black 表示 OLED 极黑（仅手动选 BLACK 时为 true）。 */
data class ResolvedUiTheme(val dark: Boolean, val black: Boolean) {
    companion object {
        val DEFAULT = ResolvedUiTheme(dark = false, black = false)
    }
}

/** 持久化 App 外观主题。默认跟随系统，也兼容旧版深色开关。 */
@Singleton
class ThemeSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        val DARK_THEME_KEY = booleanPreferencesKey("dark_theme_enabled")
        val THEME_PRESET_KEY = stringPreferencesKey("theme_preset_id")
        val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color_enabled")
        val SCHEDULE_DARK_START_KEY = stringPreferencesKey("theme_schedule_dark_start")
        val SCHEDULE_DARK_END_KEY = stringPreferencesKey("theme_schedule_dark_end")

        /** 深色时段默认 19:00–07:00（跨午夜）。 */
        const val DEFAULT_DARK_START = "19:00"
        const val DEFAULT_DARK_END = "07:00"

        /**
         * 判定 [now] 是否落在深色时段 [start, end) 内；start > end 表示跨午夜区间。
         * 解析失败的时段按不命中处理（守卫在调用方）。
         */
        fun inDarkWindow(now: java.time.LocalTime, start: java.time.LocalTime, end: java.time.LocalTime): Boolean =
            if (start <= end) now >= start && now < end else now >= start || now < end

        /** 读系统深浅：Application 级 config 由系统实时分发，不受 Activity 语言 wrapper 冻结影响。 */
        fun readSystemDark(context: Context): Boolean {
            val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return mode == Configuration.UI_MODE_NIGHT_YES
        }
    }

    /**
     * 系统深浅的实时镜像。MainActivity 的 attachBaseContext 用 createConfigurationContext
     * 只为覆盖 locale，该 wrapper 的 Configuration 里 uiMode 冻结在创建时刻，加上
     * configChanges 含 uiMode 不重建，导致 Compose 的 isSystemInDarkTheme() 读到冻结值，
     * 系统自动深浅切换时全 App 不跟随——本流是唯一可靠的系统深浅源，
     * 由 AIEditorApp.onConfigurationChanged / MainActivity.onResume 驱动更新。
     */
    private val _systemDark = MutableStateFlow(readSystemDark(context))
    val systemDark: StateFlow<Boolean> = _systemDark.asStateFlow()

    /** 系统深浅变化时同步（AIEditorApp.onConfigurationChanged / onResume 调用）。 */
    fun refreshSystemDark() {
        _systemDark.value = readSystemDark(context)
    }

    val themeModeFlow: Flow<AppThemeMode> = context.themeDataStore.data.map { prefs ->
        AppThemeMode.fromPersisted(prefs[THEME_MODE_KEY])
            ?: prefs[DARK_THEME_KEY]?.let { if (it) AppThemeMode.DARK else AppThemeMode.LIGHT }
            ?: AppThemeMode.AUTO
    }

    /** 配色主题 id，对应 `AppThemePreset.id`；未设置时为 null，由调用方回退默认。 */
    val themePresetIdFlow: Flow<String?> = context.themeDataStore.data.map { it[THEME_PRESET_KEY] }

    /** 是否启用系统莫奈取色。开启后配色主题的选择不再生效。 */
    val dynamicColorFlow: Flow<Boolean> =
        context.themeDataStore.data.map { it[DYNAMIC_COLOR_KEY] == true }

    /** 深色时段起点（HH:mm，跨午夜支持）。 */
    val scheduleDarkStartFlow: Flow<String> =
        context.themeDataStore.data.map { it[SCHEDULE_DARK_START_KEY] ?: DEFAULT_DARK_START }

    /** 深色时段终点（HH:mm）。 */
    val scheduleDarkEndFlow: Flow<String> =
        context.themeDataStore.data.map { it[SCHEDULE_DARK_END_KEY] ?: DEFAULT_DARK_END }

    suspend fun setScheduleDarkStart(value: String) {
        context.themeDataStore.edit { it[SCHEDULE_DARK_START_KEY] = value }
    }

    suspend fun setScheduleDarkEnd(value: String) {
        context.themeDataStore.edit { it[SCHEDULE_DARK_END_KEY] = value }
    }

    /**
     * 一次性解析当前应生效的深浅（冷启动首帧底色、悬浮窗等非流场景用）。
     * SCHEDULED 时段解析失败按不命中（浅色）处理，与流口径一致。
     */
    suspend fun resolveInitialDark(): Boolean {
        val mode = themeModeFlow.first()
        return when (mode) {
            AppThemeMode.AUTO -> systemDark.value
            AppThemeMode.DARK, AppThemeMode.BLACK -> true
            AppThemeMode.LIGHT -> false
            AppThemeMode.SCHEDULED -> {
                val start = runCatching { java.time.LocalTime.parse(scheduleDarkStartFlow.first()) }.getOrNull()
                val end = runCatching { java.time.LocalTime.parse(scheduleDarkEndFlow.first()) }.getOrNull()
                start != null && end != null &&
                    inDarkWindow(java.time.LocalTime.now(), start, end)
            }
        }
    }

    /**
     * 运行时主题解析流：mode + 深色时段 + 系统深浅，外加 30s 时钟取剔驱动
     * SCHEDULED 模式跨过时段边界时自动翻转（Combine 内部的时间重算）。
     */
    val resolvedThemeFlow: Flow<ResolvedUiTheme> = kotlinx.coroutines.flow.combine(
        themeModeFlow,
        scheduleDarkStartFlow,
        scheduleDarkEndFlow,
        systemDark,
        kotlinx.coroutines.flow.flow {
            while (true) {
                emit(Unit)
                kotlinx.coroutines.delay(30_000L)
            }
        }
    ) { mode, startRaw, endRaw, sys, _ ->
        val start = runCatching { java.time.LocalTime.parse(startRaw) }.getOrNull()
        val end = runCatching { java.time.LocalTime.parse(endRaw) }.getOrNull()
        val scheduledDark = start != null && end != null &&
            inDarkWindow(java.time.LocalTime.now(), start, end)
        when (mode) {
            AppThemeMode.AUTO -> ResolvedUiTheme(dark = sys, black = false)
            AppThemeMode.DARK -> ResolvedUiTheme(dark = true, black = false)
            AppThemeMode.BLACK -> ResolvedUiTheme(dark = true, black = true)
            AppThemeMode.LIGHT -> ResolvedUiTheme(dark = false, black = false)
            AppThemeMode.SCHEDULED -> ResolvedUiTheme(dark = scheduledDark, black = false)
        }
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.themeDataStore.edit { it[THEME_MODE_KEY] = mode.name }
    }

    suspend fun setThemePresetId(id: String) {
        context.themeDataStore.edit { it[THEME_PRESET_KEY] = id }
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        context.themeDataStore.edit { it[DYNAMIC_COLOR_KEY] = enabled }
    }

    /** 备份快照：返回当前持久化的主题模式名（未设置时为 null，导入时回退默认）。 */
    suspend fun snapshot(): String? = themeModeFlow.first().name

    /** 从备份还原主题模式；null 时清除键回退默认。 */
    suspend fun restore(value: String?) {
        context.themeDataStore.edit {
            if (value == null) it.remove(THEME_MODE_KEY) else it[THEME_MODE_KEY] = value
        }
    }

    /** 备份快照：当前配色主题 id。 */
    suspend fun presetSnapshot(): String? = themePresetIdFlow.first()

    /** 备份快照：莫奈取色开关。 */
    suspend fun dynamicColorSnapshot(): Boolean = dynamicColorFlow.first()

    /** 从备份还原配色主题与莫奈开关；id 为 null 时清除键回退默认。 */
    suspend fun restoreColors(presetId: String?, dynamicColor: Boolean) {
        context.themeDataStore.edit {
            if (presetId == null) it.remove(THEME_PRESET_KEY) else it[THEME_PRESET_KEY] = presetId
            it[DYNAMIC_COLOR_KEY] = dynamicColor
        }
    }
}
