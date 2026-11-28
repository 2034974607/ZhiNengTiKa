package app.zhinengtika.nativeapp.data

import android.content.Context

/** Plain, non-sensitive UI preference. Never holds credentials or business data. */
class UiPreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)

    fun themeMode(): ThemeMode = runCatching {
        ThemeMode.valueOf(preferences.getString(KEY_THEME_MODE, null) ?: ThemeMode.SYSTEM.name)
    }.getOrDefault(ThemeMode.SYSTEM)

    fun setThemeMode(mode: ThemeMode) {
        preferences.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
