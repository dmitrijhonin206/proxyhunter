package com.proxyhunter.telegram.ui.theme

import androidx.compose.ui.graphics.Color

// Набор предустановленных акцентных цветов — не алгоритмическая генерация тональной
// палитры (как Material You dynamicColorScheme, которая требует Android 12+ и обоев
// системы), а вручную подобранные пары primary/primaryContainer для света и тьмы в
// стиле baseline-палитры Material 3. onPrimary/onPrimaryContainer оставлены на дефолтах
// MaterialTheme (белый/тёмный) — они достаточно контрастны для любого из этих оттенков,
// расписывать их отдельно на каждый акцент x тему сочли избыточным для этого набора.
enum class AccentColor(
    val label: String,
    val lightPrimary: Color,
    val lightContainer: Color,
    val darkPrimary: Color,
    val darkContainer: Color,
) {
    BLUE(
        label = "Синий",
        lightPrimary = Color(0xFF2D6CDF),
        lightContainer = Color(0xFFD8E2FF),
        darkPrimary = Color(0xFF6FA8FF),
        darkContainer = Color(0xFF1D3E72),
    ),
    PURPLE(
        label = "Фиолетовый",
        lightPrimary = Color(0xFF6750A4),
        lightContainer = Color(0xFFEADDFF),
        darkPrimary = Color(0xFFD0BCFF),
        darkContainer = Color(0xFF4F378B),
    ),
    GREEN(
        label = "Зелёный",
        lightPrimary = Color(0xFF386A20),
        lightContainer = Color(0xFFB8F397),
        darkPrimary = Color(0xFF9CD67D),
        darkContainer = Color(0xFF285118),
    ),
    ORANGE(
        label = "Оранжевый",
        lightPrimary = Color(0xFF8B5000),
        lightContainer = Color(0xFFFFDDB3),
        darkPrimary = Color(0xFFFFB868),
        darkContainer = Color(0xFF6B3D00),
    ),
    RED(
        label = "Красный",
        lightPrimary = Color(0xFFBA1A1A),
        lightContainer = Color(0xFFFFDAD6),
        darkPrimary = Color(0xFFFFB4AB),
        darkContainer = Color(0xFF93000A),
    ),
    TEAL(
        label = "Бирюзовый",
        lightPrimary = Color(0xFF006A6A),
        lightContainer = Color(0xFF9DF1F0),
        darkPrimary = Color(0xFF4CDADA),
        darkContainer = Color(0xFF004F4F),
    );

    companion object {
        val Default = BLUE

        // Используется при чтении сохранённого значения из DataStore — некорректное
        // или устаревшее имя (например, после удаления акцента в будущей версии)
        // молча откатывается на дефолт, а не роняет приложение.
        fun fromNameOrDefault(name: String?): AccentColor =
            entries.find { it.name == name } ?: Default
    }
}
