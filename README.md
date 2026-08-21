# ProxyHunter for Telegram

Нативное Android-приложение (Kotlin, MVVM + Clean Architecture, Jetpack Compose) для поиска и проверки
публичных прокси, совместимых с Telegram, и их быстрого подключения через `tg://proxy` или инструкции
по ручной настройке SOCKS5/HTTP.

Полное описание архитектуры, схемы БД и рекомендаций — в `PLAN.md`.

## Сборка

Проект содержит `settings.gradle.kts`, корневой `build.gradle.kts` и `gradle/wrapper/gradle-wrapper.properties`,
но **не** содержит `gradle-wrapper.jar` (бинарный файл, генерируется автоматически при первом открытии
проекта в Android Studio — либо вручную командой `gradle wrapper`, если Gradle уже установлен локально).

```bash
git clone <repo>
cd proxyhunter
# если открываете не в Android Studio — сначала сгенерировать wrapper:
gradle wrapper --gradle-version 8.9
./gradlew assembleDebug
```

APK будет в `app/build/outputs/apk/debug/`.

### GeoIP (определение страны)
Подключён автоматически: при парсинге новых прокси (фоново через `ParsingWorker` или вручную кнопкой
"Обновить") страна резолвится пачкой для всех прокси, у которых источник не прислал её сам.
Положите `GeoLite2-Country.mmdb` (бесплатно по регистрации на maxmind.com) в `app/src/main/assets/`.

Без файла `GeoIpResolver` автоматически переключится на fallback через публичный API геолокации с кэшированием в Room.

## Запуск тестов

```bash
./gradlew testDebugUnitTest        # unit-тесты (парсер, генератор tg:// ссылок)
./gradlew connectedDebugAndroidTest # инструментальные тесты (Room DAO, Compose UI) — нужен эмулятор/устройство
```

## Установка на устройство

```bash
./gradlew installDebug
```

## Публикация в Google Play
См. раздел 7 в `PLAN.md` — там про Data Safety form, обязательный экран предупреждения о рисках,
требования к источникам парсинга и позиционирование приложения как агрегатора/чекера, а не средства
обхода защиты Telegram.
