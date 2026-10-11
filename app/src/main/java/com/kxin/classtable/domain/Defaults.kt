package com.kxin.classtable.domain

/**
 * 全应用共享的默认值。
 */
object Defaults {
    /**
     * 默认强调色「梅」。
     *
     * 此前这串十六进制在 8 处各自内联(`design/Color.kt` 的 accent 与 `AccentOptions`、
     * `design/CoursePalette.kt`、`AppSettings`、`WidgetPrefs` 多处、`SettingsRepository`、
     * `SyncRepository`),改默认色要满项目找。统一从这里取。
     */
    const val ACCENT_HEX = "#C56473"
}
