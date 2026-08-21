# ProxyHunter for Telegram — Android-приложение

Инструмент для поиска и проверки публичных прокси (SOCKS5/HTTP/HTTPS/MTProto) на совместимость с Telegram,
с быстрой настройкой найденных прокси в официальном клиенте Telegram через `tg://proxy` и системные intent'ы.
Приложение **не обходит защиту Telegram** — оно лишь агрегирует и проверяет прокси-серверы и передаёт их
в официальный клиент штатным способом.

---

## 1. Структура проекта

```
app/src/main/java/com/proxyhunter/telegram/
├── data/
│   ├── local/
│   │   ├── ProxyDatabase.kt          # Room database
│   │   ├── ProxyDao.kt
│   │   ├── CheckHistoryDao.kt
│   │   ├── entity/ProxyEntity.kt
│   │   └── entity/CheckResultEntity.kt
│   ├── remote/
│   │   ├── source/ ProxySource.kt (interface), GithubListSource.kt, ApiJsonSource.kt, CustomUrlSource.kt
│   │   ├── parser/ ProxyListParser.kt   # regex/Jsoup парсинг разных форматов
│   │   └── geoip/ GeoIpResolver.kt      # MaxMind GeoLite2 (offline .mmdb) или fallback API
│   ├── checker/
│   │   ├── ProxyChecker.kt              # TCP-connect + HTTP через прокси
│   │   ├── TelegramApiChecker.kt        # запрос к api.telegram.org через прокси
│   │   └── ParallelCheckCoordinator.kt  # ограничение параллелизма (Semaphore)
│   └── repository/
│       ├── ProxyRepositoryImpl.kt
│       └── SettingsRepositoryImpl.kt    # DataStore Preferences
├── domain/
│   ├── model/ Proxy.kt, ProxyProtocol.kt, CheckResult.kt, ProxyStatus.kt
│   ├── repository/ ProxyRepository.kt (interface), SettingsRepository.kt
│   └── usecase/
│       ├── FetchProxiesUseCase.kt
│       ├── CheckProxyUseCase.kt
│       ├── CheckAllProxiesUseCase.kt
│       ├── GenerateTelegramLinkUseCase.kt
│       └── AutoSwitchProxyUseCase.kt
├── ui/
│   ├── screens/
│   │   ├── proxylist/ ProxyListScreen.kt, ProxyListViewModel.kt
│   │   ├── details/ ProxyDetailsScreen.kt, ProxyDetailsViewModel.kt
│   │   ├── settings/ SettingsScreen.kt, SettingsViewModel.kt
│   │   └── onboarding/ RiskWarningScreen.kt   # обязательный экран предупреждения при первом запуске
│   ├── components/ ProxyCard.kt, StatusBadge.kt, FilterBar.kt, SpeedChart.kt
│   └── theme/ Color.kt, Theme.kt, Type.kt
├── worker/
│   ├── ParsingWorker.kt          # WorkManager: периодический парсинг
│   └── CheckWorker.kt            # WorkManager: периодическая проверка списка
├── di/
│   ├── DatabaseModule.kt, NetworkModule.kt, RepositoryModule.kt
├── ProxyHunterApp.kt (Application, @HiltAndroidApp)
└── MainActivity.kt
```

---

## 2. Схема базы данных (Room)

**proxies**
| поле | тип | примечание |
|---|---|---|
| id | Long (PK, autogenerate) | |
| ip | String | |
| port | Int | |
| protocol | String | SOCKS5 / HTTP / HTTPS / MTPROTO |
| username | String? | зашифровано (EncryptedSharedPreferences/SQLCipher) |
| passwordEncrypted | String? | никогда не хранится в открытом виде |
| mtprotoSecret | String? | для MTProto |
| country | String? | код страны из GeoIP |
| sourceUrl | String | откуда получен |
| addedAt | Long (timestamp) | |
| isFavorite | Boolean | |
| isCustom | Boolean | добавлен вручную пользователем |

**check_results**
| поле | тип | примечание |
|---|---|---|
| id | Long (PK) | |
| proxyId | Long (FK → proxies.id) | |
| checkedAt | Long | |
| status | String | WORKING / FAILED / TIMEOUT / NOT_CHECKED |
| latencyMs | Int? | |
| telegramApiReachable | Boolean | результат проверки именно api.telegram.org |
| errorMessage | String? | |

Связь: **Proxy 1—N CheckResult** (история проверок → график скорости на экране деталей).
Текущий статус прокси в списке — это последняя запись `check_results` для данного `proxyId` (запрос с `MAX(checkedAt)` или отдельное денормализованное поле `latestStatus` в `proxies` для быстрого списка).

**sources** (пользовательские источники парсинга)
| поле | тип |
|---|---|
| id | Long (PK) |
| url | String |
| enabled | Boolean |
| lastFetchedAt | Long? |

---

## 3. Ключевые компоненты
См. отдельные файлы:
- `ProxySource.kt` / `GithubListSource.kt` — интерфейс парсера и реализация.
- `ProxyChecker.kt` — проверка доступности + латентность + доступность Telegram API через прокси, с ограничением параллелизма.
- `ProxyRepositoryImpl.kt` — репозиторий, объединяющий Room + сетевые источники, Flow для реактивного списка.
- `ProxyListViewModel.kt` + `ProxyListScreen.kt` — Jetpack Compose UI списка с фильтрами.
- `GenerateTelegramConnectActionUseCase.kt` — генерация `tg://proxy?...` для MTProto и подготовка данных для SOCKS5/HTTP.

---

## 4. Разрешения в манифесте

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<!-- Для WorkManager фонового парсинга/проверки — обычный INTERNET/ACCESS_NETWORK_STATE достаточно,
     VpnService НЕ используется в MVP согласно ТЗ (опционально в будущих версиях). -->
```

`AndroidManifest.xml` также должен объявлять `queries` для intent-запросов к Telegram (Android 11+ package visibility):
```xml
<queries>
    <package android:name="org.telegram.messenger" />
    <intent>
        <action android:name="android.intent.action.VIEW" />
        <data android:scheme="tg" />
    </intent>
</queries>
```

---

## 5. Сборка и запуск

### Зависимости (`app/build.gradle.kts`, основное)
```kotlin
dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("org.jsoup:jsoup:1.18.1")

    // Coroutines / WorkManager / DataStore
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // GeoIP (offline)
    implementation("com.maxmind.geoip2:geoip2:4.2.1")

    // Тесты
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
```

### Команды
```bash
# Сборка debug APK
./gradlew assembleDebug

# Запуск unit-тестов
./gradlew testDebugUnitTest

# Запуск инструментальных тестов (Room, на эмуляторе/устройстве)
./gradlew connectedDebugAndroidTest

# Установка на подключённое устройство
./gradlew installDebug
```

Для работы GeoIP-определения страны нужно положить файл `GeoLite2-Country.mmdb` (бесплатная лицензия MaxMind, требуется регистрация) в `app/src/main/assets/` — либо использовать fallback через публичный API геолокации по IP с кэшированием результатов в Room, чтобы не делать повторных запросов.

---

## 5.1 Фоновая работа, GeoIP и DI — как это связано

**WorkManager (`worker/`)**
- `ParsingWorker` / `CheckWorker` — `CoroutineWorker` с `@HiltWorker`, получают `ProxyRepository`/`SettingsRepository`/`ProxyHunterNotifier` через конструкторный DI. Для этого `ProxyHunterApp` реализует `Configuration.Provider` и подставляет `HiltWorkerFactory` — без этого Hilt-инжект в Worker'ы не заработает, т.к. WorkManager по умолчанию создаёт их через reflection с пустым конструктором.
- `WorkScheduler` — единая точка постановки *периодической* работы: `schedulePeriodicParsing(hours)` / `schedulePeriodicCheck(hours)` (интервал берётся из `SettingsRepository`, смена в настройках сразу вызывает reschedule через `ExistingPeriodicWorkPolicy.UPDATE`). Кнопки "Обновить" / "Проверить все" на экране списка вызывают `ProxyRepository.refreshFromSources()/checkAll()` напрямую из `ProxyListViewModel` (не через WorkManager) — это даёт немедленное состояние `isRefreshing`/`isChecking` в UI без необходимости подписываться на `WorkInfo`. `WorkScheduler.runParsingNow()/runCheckNow()` существуют как готовый API для постановки разовой работы вне UI (например, будущий quick-settings tile или shortcut), но сейчас на кнопки не заведены.
- Оба воркера используют `Constraints(NetworkType.CONNECTED)` и экспоненциальный backoff при ретраях.
- `CheckWorker` реализует автопереключение из ТЗ: если прокси, помеченный в `SettingsRepository` как активный, перестал быть `WORKING`, ищется лучшая рабочая замена (`ProxyRepository.findBestWorkingProxy`) и отправляется уведомление через `ProxyHunterNotifier` — переключение не происходит автоматически без участия пользователя (открытие Telegram с новым прокси всё ещё требует тапа), это осознанное решение в пользу предсказуемости для пользователя.

**GeoIP (`data/remote/geoip/GeoIpResolver.kt`)**
- Сначала пробует офлайн-резолв через MaxMind `GeoLite2-Country.mmdb` из `assets/` (без сети, без лимитов запросов).
- Если файла нет или IP не резолвится офлайн — fallback на публичный `ip-api.com` с результатом, закэшированным в Room (`geo_cache`) на 30 дней, чтобы не бить по рейт-лимитам бесплатного API при повторных проверках одного и того же IP.
- Подключён в `ProxyRepositoryImpl.refreshFromSources()`, а не напрямую в `ParsingWorker` — это единая точка, где спарсенные `Proxy` превращаются в сохраняемые записи, и её вызывают оба пути обновления списка (фоновый `ParsingWorker` и кнопка "Обновить" в `ProxyListViewModel`), так что резолвинг страны работает в обоих случаях без дублирования логики. `resolveMissingCountries()` резолвит пачкой (`resolveBatch`) только те прокси, у которых источник не прислал страну сам (например, `JsonListSource` иногда уже даёт `country` в самих данных — такие не трогаем), чтобы не делать сетевой запрос на каждый IP по отдельности и не блокировать вставку в БД.

**DI (`di/`)**
- `DatabaseModule` — Room + DAO providers.
- `NetworkModule` — общий `OkHttpClient`, `ProxySourceRegistry`, `ProxyChecker`.
- `WorkModule` — `WorkManager.getInstance(context)`.
- `RepositoryModule` — биндинг `ProxyRepository → ProxyRepositoryImpl` через `@Binds`.

## 5.2 Тестовое покрытие (реализовано в скелете)

- `ProxySourceParsingTest` — парсер построчных списков (`ip:port`, `ip:port:protocol`, невалидные строки), JSON-источник (включая MTProto-секрет и невалидный JSON), HTML-таблица через Jsoup с пропуском битых строк. Сеть замокана через `okhttp3.mockwebserver.MockWebServer`, без обращений к реальным сайтам.
- `ProxyCheckerTest` — TCP-доступность (открытый/закрытый порт), доступность Telegram API через HTTP-прокси (запрос заворачивается на `MockWebServer`, `telegramApiProbeUrl` сделан переопределяемым специально для тестируемости), MTProto-handshake через собственный сырой TCP-стаб (принял/закрыл/ответил), fallback на fake-TLS секретах, и параллельная проверка списка через `checkAll`.
- `GenerateTelegramConnectActionUseCaseTest` — корректность `tg://proxy` ссылки с секретом и без, содержимое инструкции для SOCKS5/HTTP, включение логина в текст для копирования.
- Инструментальные тесты Room (`ProxyDaoTest` в `androidTest/`) — фильтрация по протоколу/статусу/избранному/поиску, сортировка по латентности, дедупликация по уникальному индексу `(ip, port)`, `getBestWorking` для автопереключения, `purgeStale` (удаляет только старые некастомные прокси), история проверок и GeoIP-кэш. Требуют эмулятор/устройство — запускаются через `./gradlew connectedDebugAndroidTest`, а не как обычные JVM unit-тесты.

## 5.3 Экраны и навигация (реализовано в скелете)

- `MainActivity` — точка входа, запрашивает `POST_NOTIFICATIONS` на Android 13+, применяет `ProxyHunterTheme` (светлая/тёмная, читается из `SettingsRepository` через `ThemeViewModel`) и рендерит `ProxyHunterNavHost`.
- `ProxyHunterNavHost` — граф навигации: `RiskWarningScreen` (обязательный гейт при первом запуске, флаг `hasSeenRiskWarning` в DataStore) → `ProxyListScreen` → `ProxyDetailsScreen` / `SettingsScreen`. Экран предупреждения не остаётся в back stack после подтверждения (`popUpTo(inclusive = true)`).
- `ProxyDetailsScreen` — полная информация о прокси, простой линейный график латентности на `Canvas` (без сторонних чарт-библиотек — достаточно для MVP), кнопки «Проверить снова» и избранное.
- `SettingsScreen` — интервалы фонового парсинга/проверки и таймаут проверки через `FilterChip`, переключатель темы (системная/светлая/тёмная), добавление пользовательских источников парсинга. Смена интервала сразу переставляет периодическую работу в `WorkManager` через `WorkScheduler.schedulePeriodicParsing/Check`, без перезапуска приложения.
- Минимальные ресурсы (`strings.xml`, `themes.xml`, адаптивная иконка, иконка уведомления) добавлены, чтобы манифест и код собирались. Растровые PNG-иконки (`mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png`) сгенерированы как fallback для API < 26 — adaptive icon (векторная) работает только с 26+, а `minSdk = 24`.

## 5.4 Активный прокси и автопереключение — доведено до конца

Раньше `CheckWorker` читал `SettingsRepository.getActiveProxyId()`, но никто в UI его не устанавливал — автопереключение фактически не имело от чего отталкиваться. Теперь:

- `SettingsRepository.activeProxyId` — реактивный `Flow<Long?>` поверх того же DataStore-ключа, что использует `CheckWorker` (`getActiveProxyId()` suspend-версия для воркера осталась без изменений).
- `ProxyListViewModel` подписан на этот Flow и включает `activeProxyId` в `ProxyListUiState`. Диалог подключения (`onUseInTelegram`) сам по себе НЕ помечает прокси активным — только открывает диалог. Активным прокси становится, только когда пользователь реально подтверждает действие: жмёт «Открыть в Telegram» (для MTProto deep-link) или «Скопировать параметры» (для SOCKS5/HTTP) — это момент вызова `confirmUsingProxy()`. Так `CheckWorker` не начинает присматривать за прокси, который пользователь просто посмотрел в диалоге, но не выбрал.
- `ProxyListScreen` показывает бейдж «Используется» на карточке активного прокси.
- Побочно добавлен `FilterBar.kt` — компонент фильтров (поиск, протокол, статус, избранное, сортировка), который упоминался в структуре проекта, но не был реализован раньше.

## 5.5 MTProto transport-handshake — вместо голого TCP-connect

Раньше MTProto-прокси проверялись только TCP-connect'ом до порта — это ловит "порт закрыт", но не отличает реальный MTProxy от любого другого TCP-сервиса на том же порту (например, случайно открытый SSH или мёртвый прокси другого типа). Теперь `data/checker/mtproto/MtProtoObfuscatedHandshakeBuilder.kt` строит настоящий handshake-пакет транспортной обфускации MTProto — протокол публично задокументирован Telegram (`core.telegram.org/mtproto/mtproto-transports#transport-obfuscation`):

1. Генерируется 64-байтовый случайный заголовок с проверкой запрещённых значений (первый байт ≠ `0xef`, первые 4 байта не совпадают с сигнатурами HTTP-методов или другими зарезервированными тегами, следующие 4 байта не все нулевые).
2. В байты `[56:60)` подставляется тег транспорта (реализован `INTERMEDIATE`, есть также `ABRIDGED`/`PADDED_INTERMEDIATE`).
3. Из байтов заголовка выводятся ключ+IV для исходящего потока (прямой порядок) и для входящего (тот же диапазон байт, но развёрнутый) — AES-256-CTR через `javax.crypto.Cipher`.
4. Если задан секрет — ключ усиливается `SHA-256(keyMaterial + secret)`. Секрет с префиксом `dd` (padded) обрабатывается как классический. Секрет с префиксом `ee` (fake-TLS, заворачивает всё в поддельный TLS ClientHello) **не поддерживается** — это отдельный протокол поверх obfuscated2, и его реализация означала бы, по сути, писать TLS-стек; в этом случае `checkMtProto` в `ProxyChecker.kt` честно откатывается на TCP-only проверку с пометкой в `errorMessage`, а не выдаёт ложный вердикт.
5. Весь заголовок шифруется собственным keystream'ом, последние 8 байт заменяются на их шифротекст — это прячет тег транспорта на проводе и одновременно продвигает keystream исходящего потока на 64 байта вперёд для последующих (гипотетических) данных.

**Что проверяется дальше.** Полноценный auth-key exchange (реальная авторизация в Telegram) сознательно не реализован — это по объёму сопоставимо с написанием клиента Telegram с нуля и избыточно для задачи "жив ли прокси". Вместо этого `checkMtProto` отправляет собранный заголовок и интерпретирует реакцию сервера в течение `mtprotoReadTimeoutMs`:
- соединение закрылось сразу (EOF) → `FAILED`, прокси отверг заголовок;
- сервер прислал хоть один байт → `WORKING`;
- таймаут чтения без разрыва соединения → `WORKING` — это ожидаемое поведение живого MTProxy, который принял корректный заголовок и молча ждёт настоящих MTProto-данных, а не закрывает сокет.

Это заметно надёжнее исходного TCP-connect (отличает молчаливый-но-живой MTProxy от сервиса, который сразу рвёт соединение на непонятных ему байтах), но остаётся эвристикой, а не полной верификацией протокола — прокси, который принимает handshake, но затем ведёт себя некорректно на уровне реальных MTProto-сообщений, всё ещё может быть помечен как `WORKING`.

Тесты: `MtProtoObfuscatedHandshakeBuilderTest` (структурные инварианты заголовка, обработка форматов секрета — classic/dd/ee/невалидный) и обновлённый `ProxyCheckerTest` (три сценария реакции сервера через собственный `FakeRawTcpServer` на сыром `ServerSocket`, плюс fallback на fake-TLS секрете) — `MockWebServer` для этого не подошёл, так как это HTTP-сервер, а handshake — бинарный протокол поверх голого TCP.

## 5.6 Ручное добавление прокси

`AddProxyScreen.kt` + `AddProxyViewModel.kt` — форма с выбором протокола (чипы), IP, портом, и, в зависимости от протокола, либо логином/паролем (SOCKS5/HTTP/HTTPS), либо MTProto-секретом. Все поля кроме IP/порта необязательны — публичные прокси часто без авторизации. Открывается через FAB на `ProxyListScreen`, добавлен маршрут `add_proxy` в `ProxyHunterNavHost`.

Валидация вынесена в `domain/usecase/ValidateProxyInputUseCase.kt` (не UI-деталь, а бизнес-правило — что считается валидным IP/портом/секретом), пересчитывается на каждое изменение поля, но ошибки показываются только после попытки сохранить (`showErrors`), чтобы не подсвечивать поля красным до того, как пользователь вообще начал печатать. Формат MTProto-секрета в валидаторе намеренно зеркалит то, что реально понимает `MtProtoObfuscatedHandshakeBuilder.parseSecret` (пусто / 32 hex-символа / 34 символа с префиксом `dd`/`ee`) — включая `ee`-секреты, формат которых валиден на вводе, даже если фактическая проверка такого прокси потом деградирует до TCP-only (см. 5.5).

## 5.7 Ручная сверка вместо реальной сборки — что нашлось и исправлено

Сетевой доступ в среде разработки заблокирован, Android SDK/Gradle/Kotlin-компилятор недоступны — прогнать настоящую сборку и `./gradlew test` было нельзя. Вместо этого сделан построчный ручной проход по всем файлам (импорты, сигнатуры, DI-граф). Найдено и исправлено:

1. **Дублирующие Dagger-биндинги.** `ProxyChecker` и `ProxySourceRegistry` уже имеют `@Inject constructor`, но в `NetworkModule` для них были ещё и explicit `@Provides` — Dagger отказался бы собираться с "is bound multiple times". Провайдеры убраны; `@Singleton` перенесён на сам класс `ProxySourceRegistry` (раньше скоуп держался только на удалённом провайдере).
2. **`CheckWorker`/`ParsingWorker` не скомпилировались бы.** `override suspend fun doWork(): Result` без явного `import androidx.work.ListenableWorker.Result` резолвил `Result` в `kotlin.Result`, у которого нет `.retry()` — компилятор упал бы на `Result.retry()`/`Result.failure()` внутри тела. Добавлен явный импорт в оба файла.
3. **Отсутствовали корневые Gradle-файлы** — `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`. Без них это не было валидным Gradle-проектом независимо от сети. Досозданы. `gradle-wrapper.jar` — бинарник, скачать без сети нельзя; при первом открытии в Android Studio или командой `gradle wrapper` (если Gradle уже установлен локально) он сгенерируется автоматически.
4. **`exportSchema = true`** в `ProxyDatabase` без настроенного `room.schemaLocation` в `build.gradle.kts` — не ошибка, но build-warning на каждую сборку. Переключено на `false` с комментарием, когда включать обратно.

Систематически сверено (совпадение 1:1, без расхождений): все `@Inject constructor` — против того, что реально предоставляется в DI-модулях; интерфейс `ProxyRepository` (9 методов) — против переопределений в `ProxyRepositoryImpl`; каждый вызов `dao.*`/`checkHistoryDao.*`/`geoCacheDao.*`/`checker.*`/`sourceRegistry.*`/`crypto.*`/`settings.*`/`workScheduler.*`/`notifier.*` — против реальных объявлений; сигнатуры всех 5 экранов (`RiskWarningScreen`, `ProxyListScreen`, `AddProxyScreen`, `ProxyDetailsScreen`, `SettingsScreen`) — против вызовов в `ProxyHunterNavHost`; поля `state.*` и вызовы `viewModel::*` в каждом экране — против соответствующих `UiState`/`ViewModel`; цветовые константы в `Theme.kt` — против `Color.kt`; все тестовые файлы — против публичного API проверяемых классов.

Также найдена и исправлена неточность в тексте плана (5.1): кнопки "Обновить"/"Проверить все" по факту вызывают `ProxyRepository` напрямую из `ProxyListViewModel`, а не через `WorkScheduler.runParsingNow()/runCheckNow()`, как было написано раньше — исправлено на фактическое поведение.

**Что осталось непроверенным без настоящего компилятора и Room annotation processor'а:** корректность SQL в `@Query`-аннотациях `ProxyDao` (проверяется только собственным Room-процессором при сборке); точная типизация в паре мест с выводом типов Compose; и отдельно как поведенческая, не компиляционная особенность — `withTimeoutOrNull` в `checkMtProto` оборачивает блокирующий `Socket` I/O, поэтому не прерывает его строго на `CHECK_TIMEOUT_MS` (реальный worst-case — сумма `TCP_CONNECT_TIMEOUT_MS` + `mtprotoReadTimeoutMs`, что близко к номиналу, но не идентично ему).

Покрыто `ValidateProxyInputUseCaseTest` — граничные случаи IP (ведущие нули, диапазон октетов), порта (0, 65536, границы 1/65535), и всех форматов MTProto-секрета.

## 5.8 GeoIP подключён к парсингу

`ProxyRepositoryImpl` теперь принимает `GeoIpResolver` через конструктор (обычный `@Inject`, дублирующего `@Provides` для него нет — та же осторожность, что и для `ProxyChecker`/`ProxySourceRegistry` в 5.7). В `refreshFromSources()` после сбора прокси со всех источников вызывается `resolveMissingCountries()`: собирает IP тех прокси, у кого `country == null` (источник вроде `JsonListSource` иногда уже присылает страну сам — таких не трогаем), резолвит их одним вызовом `GeoIpResolver.resolveBatch()`, и подставляет результат перед вставкой в Room.

Поскольку и фоновый `ParsingWorker`, и кнопка "Обновить" в `ProxyListViewModel` вызывают один и тот же `refreshFromSources()`, отдельно трогать `ParsingWorker.kt` не понадобилось — резолвинг страны работает по обоим путям обновления списка.

Покрыто `ProxyRepositoryImplTest` — первый тест в проекте на **MockK** (`io.mockk:mockk`, добавлен в `testImplementation`), а не Mockito: работает с финальными Kotlin-классами без модификации самих классов (`open`) и без реального Android-окружения — `CryptoManager`/`GeoIpResolver` в тестах вообще не вызывают свои настоящие конструкторы (Android Keystore, MaxMind), MockK создаёт proxy-подмену поверх типа, минуя тело методов. Тесты: резолвится страна только для прокси без неё; при уже полном наборе стран `resolveBatch` не вызывается вовсе; несколько IP без страны уходят одним батч-вызовом, а не по одному; подсчёт вставленных корректно игнорирует дубликаты (`-1` от Room); результаты нескольких источников объединяются; падение одного источника не роняет весь `refreshFromSources()` (см. `runCatching` вокруг `source.fetch()`).

## 5.9 Экспорт / импорт списка (JSON, CSV)

**`data/export/ProxyExportSerializer.kt`** — конвертация `List<Proxy>` в строку и обратно, в обе стороны, для двух форматов:
- **JSON** — через `Moshi` (провайдер добавлен в `NetworkModule` — раньше `Moshi` нигде не была подключена через Hilt, только руками в тестах) с `KotlinJsonAdapterFactory` (та же reflection-based схема, что уже использовалась для `JsonListSource`/`JsonProxyDto` при парсинге источников), с `.indent("  ")` для читаемого форматирования.
- **CSV** — минимальный самописный RFC4180-совместимый парсер/writer (полей с запятой или кавычками внутри — например, пароль с запятой — оборачиваются в кавычки, кавычки внутри экранируются удвоением `""`), без внешней CSV-библиотеки.

Экспортная запись (`ProxyExportRecord`) сознательно не совпадает 1:1 с `Proxy` — без `id`/`latestStatus`/`lastCheckedAt`/`isCustom` и т.д., это runtime-состояние конкретной установки, переносить между устройствами его не имеет смысла. Импортированные прокси всегда попадают в базу как новые записи с `sourceUrl = "import"`, `isCustom = true` (не будут удалены фоновой очисткой `purgeStale`, которая трогает только некастомные устаревшие записи).

Обе стороны (`toJson`/`toCsv` и `fromJson`/`fromCsv`) не бросают на частично некорректных данных — `ImportParseResult(proxies, skippedRows)` считает, а не молча теряет строки/записи с неизвестным протоколом, некорректным портом или недостаточным числом CSV-колонок — так UI может честно показать "импортировано N, пропущено M" вместо тихой потери части файла.

**Репозиторий**: `ProxyRepository` получил `getAllProxies(): List<Proxy>` (полный снапшот для экспорта — новый `ProxyDao.getAll()`, без фильтров, в отличие от `observeProxies`, которая всегда фильтрует под текущий вид списка) и `importProxies(proxies: List<Proxy>): Int` (переиспользует ту же дедупликацию по `(ip, port)` через `OnConflictStrategy.IGNORE`, что и `refreshFromSources`, и ту же `resolveMissingCountries()` — если в CSV/JSON нет колонки страны, она резолвится через `GeoIpResolver` при импорте так же, как при обычном парсинге).

**UI**: в `SettingsScreen` — кнопки "Экспорт JSON" / "Экспорт CSV" (через `ActivityResultContracts.CreateDocument(mimeType)` — пользователь сам выбирает имя файла и папку через системный диалог Storage Access Framework, приложению не нужны разрешения на хранилище) и "Импортировать список" (через `ActivityResultContracts.OpenDocument()`). Формат импортируемого файла определяется по содержимому (JSON начинается с `[` после trim), а не по расширению — `content://` URI из системного пикера не всегда даёт удобный доступ к оригинальному имени файла. Результат показывается через `Snackbar` (одноразовое сообщение в `SettingsUiState.exportImportMessage`, сбрасывается через `consumeExportImportMessage()` — тот же паттерн, что и `pendingConnect` в `ProxyListViewModel`).

Чтение/запись файла по `Uri` идёт через `ContentResolver` в `SettingsViewModel`, для чего туда добавлен `@ApplicationContext Context` — оправдано для файлового I/O через SAF (в отличие от `Activity`-контекста, `applicationContext` безопасно держать во ViewModel сколь угодно долго).

При добавлении `combine()` шестого потока (`exportImportState`) в `SettingsViewModel` наткнулся на ту же ловушку типов, что уже чинил в `ProxyListViewModel`: типизированный `combine()` поддерживает разнотипные потоки только до 5 включительно, vararg-версия требует одинаковый тип для всех. Решение то же — вложенный `combine()`: сначала пять "настроечных" потоков собираются в `settingsState`, затем он комбинируется отдельным вызовом с `exportImportState`.

Покрыто `ProxyExportSerializerTest` (round-trip JSON и CSV с сохранением всех полей, неизвестный протокол считается как `skippedRows`, битый JSON не бросает исключение, CSV-поля с запятой/кавычками переживают экспорт-импорт без потерь, файл без заголовка всё равно парсится, короткие/некорректные CSV-строки пропускаются и считаются) и двумя дополнительными тестами в `ProxyRepositoryImplTest` (`getAllProxies` не трогает geo-резолвер; `importProxies` резолвит недостающую страну и помечает прокси кастомными; подсчёт игнорирует дубликаты).

## 5.10 VPN-режим — честная граница реализации

VPN-режим из необязательного пункта ТЗ ("создавать локальный VPN-туннель через VpnService") реализован, но с явно задокументированной и осознанной границей — а не притворяется полным, когда по факту таким быть не может в разумном объёме написанного вручную Kotlin-кода.

**Что реально работает:**
- `worker/vpn/ProxyHunterVpnService.kt` — полноценный жизненный цикл `VpnService`: `Builder` (адрес туннеля, маршрут "весь трафик", DNS, MTU), foreground-уведомление (обязательно для долгоживущего VPN-сервиса), `onRevoke()` (пользователь мог отозвать разрешение через системные настройки, а не через UI приложения — это отдельный колбэк, который легко забыть), корректная очистка `ParcelFileDescriptor` при остановке.
- Системный диалог согласия (`VpnService.prepare()`) корректно встроен в поток UI (`ProxyDetailsScreen`) через `ActivityResultContracts.StartActivityForResult` — сам запрос согласия требует Activity-контекста и не может жить во ViewModel, поэтому разделён: проверка/показ диалога на экране, а сам старт сервиса (`ViewModel.startVpn()`) вызывается уже после подтверждения.
- **UDP реально ретранслируется через прокси** — не напрямую в интернет: `worker/vpn/Socks5UdpAssociateClient.kt` реализует клиентскую часть SOCKS5 UDP ASSOCIATE (RFC 1928 §7, RFC 1929 для авторизации) — TCP control-соединение для хендшейка, затем UDP-датаграммы оборачиваются в SOCKS5 UDP-заголовок и шлются на relay-адрес, который вернул прокси. Без этого шага VPN-режим на самом деле бы прокладывал трафик мимо прокси напрямую — что было бы прямо противоположно заявленной цели и вводило бы пользователя в заблуждение насчёт приватности.
- `worker/vpn/Ipv4PacketUtils.kt` — чистый (без сокетов) разбор/сборка сырых IPv4+UDP пакетов из TUN-интерфейса, включая корректный расчёт обязательной IP-заголовочной чексуммы (UDP-чексумма оставлена нулевой — для IPv4 это валидное "не используется", RFC 768).
- `VpnService.protect()` вызывается на обоих сокетах (TCP control и UDP relay) — без этого их собственный трафик снова попал бы в туннель и зациклился, это частая ошибка в самодельных VPN-реализациях.
- **VPN-режим доступен только для SOCKS5-прокси** — кнопка в `ProxyDetailsScreen` скрыта (не просто задизейблена — задизейбленная кнопка без объяснения читалась бы как баг) для HTTP/HTTPS (у HTTP-прокси нет UDP-релея в принципе, только TCP CONNECT) и для MTProto (это Telegram-специфичный прикладной релей поверх MTProto-фрейминга, а не общий IP/SOCKS-шлюз — понятие "провести через него весь трафик устройства" для него попросту не имеет смысла).

**Что НЕ реализовано и почему это архитектурная граница, а не забытая недоделка:**
TCP-пакеты из TUN-интерфейса распознаются (`TunnelEngine.droppedTcpPackets`), но не ретранслируются. Корректная ретрансляция TCP поверх сырых IP-пакетов требует полноценного пользовательского TCP/IP-стека — обработка SYN/ACK/FIN, ретрансмиссий, окон, MSS, состояний соединения. Это отдельный, объёмный проект сам по себе (тысячи строк кода), именно поэтому в продакшн-приложениях для этого используют готовые нативные библиотеки через JNI, а не пишут разбор на Kotlin с нуля:
- [go-tun2socks](https://github.com/eycorsican/go-tun2socks) (использует Outline)
- [badvpn-tun2socks](https://github.com/ambrop72/badvpn)
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)

Практическое следствие: сейчас VPN-режим полезен для UDP-трафика (включая DNS), но НЕ переносит обычный TCP-трафик Telegram (MTProto по умолчанию работает поверх TCP) — то есть в текущем виде VPN-режим не заменяет собой настройку прокси в самом Telegram (`GenerateTelegramConnectActionUseCase`) для обычной переписки. Честно отражено в тексте на экране (`VpnSection` в `ProxyDetailsScreen`): "TCP-трафик сейчас не поддерживается VPN-режимом".

**Манифест**: добавлен `FOREGROUND_SERVICE` permission и регистрация `<service>` с `android:permission="BIND_VPN_SERVICE"` и `<intent-filter><action android:name="android.net.VpnService" /></intent-filter>` — стандартный, стабильный паттерн, не менявшийся много лет. Отдельно НЕ проставлен `android:foregroundServiceType` — точные требования к этому атрибуту для VPN-сервисов зависят от `targetSdk` и менялись между версиями Android; вместо того чтобы угадать конкретное значение и рискнуть вписать неверное, оставлено как явный TODO — свериться с актуальной документацией под тот `targetSdk`, с которым проект реально будет собираться.

**Синхронизация состояния между сервисом и UI**: `worker/vpn/VpnStateHolder.kt` — Hilt-синглтон с `StateFlow<Long?>` (id прокси с активным VPN, либо `null`). `VpnService` — не `ViewModel`, напрямую с Compose-состоянием не связан, поэтому оба (сервис и `ProxyDetailsViewModel`) читают/пишут в общий синглтон, чтобы кнопка на экране корректно отражала состояние, даже если экран был переоткрыт уже после запуска туннеля.

Покрыто `Ipv4PacketUtilsTest` (разбор/сборка IPv4+UDP пакетов, round-trip, корректность checksum через self-check инвариант — если посчитать чексумму над пакетом с уже выставленным полем чексуммы, результат должен быть 0, RFC 791) и `Socks5UdpAssociateFramingTest` (обёртка/разбёртка SOCKS5 UDP-датаграмм, фрагментация и не-IPv4 адреса корректно отклоняются). Живые сокеты (TCP-хендшейк с реальным SOCKS5-сервером, сам `TunnelEngine.run()` цикл чтения TUN) не протестированы — это уже интеграционный уровень, требующий либо реального прокси-сервера в тесте, либо серьёзного мокирования `Socket`/`DatagramSocket`, что не сделано в рамках этого шага.

При написании тестов на этот код дважды наступил на грабли, которые не встречались раньше в проекте, и стоит зафиксировать: **`org.junit.Assert.assertNotNull(x)` — это обычный статический Java-метод без Kotlin-контракта**, он не даёт компилятору smart-cast для последующих обращений к `x` в том же блоке (в отличие от `if (x != null) { ... }`). Первая черновая версия тестов писала `assertNotNull(header); assertEquals(20, header.headerLength)` — это НЕ скомпилировалось бы. Исправлено на паттерн `assertNotNull(result); val header = requireNotNull(result); header.headerLength` везде в проекте, и заодно прогнана проверка всех остальных существующих тестовых файлов на тот же паттерн — больше нигде не встретился.

## 6. Рекомендации по тестированию


- **Unit**: `ProxyListParser` — тесты на разные форматы источников (построчный `ip:port`, JSON-массив, HTML-таблица через Jsoup); `GenerateTelegramLinkUseCase` — корректность генерируемых `tg://proxy` ссылок с/без секрета.
- **Integration**: Room DAO через `room-testing` + in-memory database; `ProxyChecker` — с mock-сервером (OkHttp `MockWebServer`) вместо реальных прокси в CI.
- **UI**: Compose UI-тесты для `ProxyListScreen` (фильтрация, пустые состояния, индикация статуса).
- Мокать сеть в CI — не гонять реальную проверку публичных прокси в pipeline (нестабильно и медленно).

## 7. Публикация в Google Play

Разберу по шагам: что именно нужно заполнить в Play Console, какие декларации специфичны именно для этого приложения (прокси + VPN), и что реально удлиняет ревью для этой категории приложений.

### 7.1 Аккаунт и базовая настройка

- **Google Play Console аккаунт** (разовый взнос $25, привязка к Google-аккаунту разработчика — организации потребуется D-U-N-S номер для верификации, если публикация от юрлица).
- **Приватная политика (Privacy Policy)** — обязательна для любого приложения, тем более работающего с сетевым трафиком и (в перспективе) с VPN. Должна быть доступна по постоянному публичному URL (не Google Docs со сроком жизни) и явно описывать: какие данные приложение обрабатывает (список прокси, история проверок — хранятся локально на устройстве, не на сервере разработчика), что трафик идёт через сторонние прокси-серверы вне контроля разработчика, и что VPN-режим (если включён) направляет UDP-трафик устройства через выбранный пользователем сервер.
- **Название приложения и разработчика** — "ProxyHunter for Telegram" не должно вводить в заблуждение насчёт официальной связи с Telegram (Telegram FZ-LLC) — в описании стоит явно указать "неофициальное приложение, не аффилировано с Telegram".

### 7.2 App content — декларации в Play Console

Это отдельный раздел Play Console ("Policy" → "App content"), и для этого приложения актуальны сразу несколько специфичных пунктов:

**Data Safety form** — самый важный раздел для прокси/VPN-приложения:
- Указать, что приложение передаёт сетевой трафик пользователя через сторонние серверы (прокси-листы из открытых источников), не контролируемые разработчиком.
- IP-адреса и данные о подключении технически обрабатываются (для GeoIP и проверки прокси) — задекларировать как минимум "App activity" / "Device or other IDs" в зависимости от того, что реально логируется. Поскольку по архитектуре (см. `PLAN.md` разделы 1–2) все данные хранятся локально в Room на устройстве и никуда на сервер разработчика не уходят — важно явно отразить это ("Data is not collected" применительно к серверам разработчика, но НЕ применительно к сторонним прокси, через которые физически идёт трафик — это разные вещи, и форма разделяет "collected by developer" и общее описание функциональности).
- Шифрование данных при передаче — указать, что чувствительные данные (логин/пароль прокси) шифруются на устройстве (`CryptoManager`, AES-GCM через Android Keystore — см. раздел 2).

**VPN Service declaration** — с добавлением VPN-режима (раздел 5.10) это отдельная декларация в Play Console: Google требует явно указать, что приложение использует `android.net.VpnService`, обосновать цель (в данном случае — маршрутизация трафика через пользовательский прокси-сервер для обхода сетевых ограничений) и подтвердить, что приложение не является собственно VPN-сервисом-посредником разработчика (трафик не проходит через сервера разработчика — прокси выбирает и настраивает сам пользователь). **Важно:** приложения с `VpnService` проходят дополнительное, часто ручное ревью — закладывать на это отдельное время сверх обычных 1–3 дней (может растянуться на 1–2 недели, особенно при первой публикации).

**Permissions declaration** — `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS` обычно не требуют отдельного обоснования, но если Google запросит Permissions Declaration Form (иногда триггерится для чувствительных разрешений) — обосновать `FOREGROUND_SERVICE` явно необходимостью держать VPN-туннель и фоновую проверку прокси активными.

**Target audience and content** — указать возрастную категорию (скорее всего 18+, учитывая, что приложение работает с обходом сетевых ограничений и по сути является security/networking-инструментом, не предназначенным для детей) — это также влияет на то, что нельзя таргетировать приложение как "для семейного/детского контента" (Families Policy) даже случайно через неверный выбор категории.

**Government apps / News apps / COVID-19 apps** — неприменимо, отметить "нет" по всем.

### 7.3 Специфичные политики для прокси/VPN-категории

- **User Data policy** — раз приложение обрабатывает сетевые данные пользователя (даже локально), нужна отдельная, видимая в самом приложении ссылка на privacy policy (не только в Play Store листинге) — например, в `SettingsScreen`.
- **Deceptive Behavior policy** — прокси-приложения — частая мишень отклонений за "misleading claims" (обещания скорости/анонимности/безопасности, которые приложение не может гарантировать, раз использует непроверенные публичные прокси). В описании и на скриншотах избегать формулировок вроде "100% анонимно", "военное шифрование" и подобных непроверяемых заявлений — честно писать "агрегирует публичные прокси-серверы, работоспособность и безопасность которых не гарантируется разработчиком" (это прямо соответствует уже реализованному `RiskWarningScreen`, см. раздел 1).
- **Impersonation** — само название/иконка не должны визуально имитировать официальное приложение Telegram (логотип, цветовая схема) — стоит свериться, что `ic_launcher` (раздел 5.3) визуально достаточно отличается от официальной иконки Telegram.
- **Malicious Behavior / Mobile Unwanted Software** — поскольку приложение парсит списки с внешних URL (`ProxySourceRegistry`, раздел 5.1) — на всякий случай не включать в built-in источники домены с сомнительной репутацией; для пользовательских источников (raздел 5.6/добавление вручную) уместно предупреждение при первом использовании нестандартного URL.

### 7.4 Testing tracks и постепенный релиз

1. **Internal testing** — сразу после первой сборки, до заполнения всех форм; до 100 тестировщиков по email-списку, публикуется мгновенно.
2. **Closed testing** (минимум 14 дней с минимум 12 активными тестировщиками для новых аккаунтов разработчика — это официальное требование Google для получения доступа к Production track с 2023 года) — сюда стоит включить реальную проверку VPN-режима на разных версиях Android (особенно 12/13/14 из-за меняющихся foreground-service-type требований, см. раздел 5.10).
3. **Open testing** — опционально, для более широкого фидбека перед полным релизом.
4. **Production** — постепенный rollout (staged rollout, начиная с 5–10%) вместо 100% сразу — особенно важно для приложения с VPN-функциональностью, где баг в `TunnelEngine` может временно оборвать пользователю интернет-соединение.

### 7.5 Store listing — что подготовить

- Иконка (уже есть adaptive + raster fallback, раздел 5.3), скриншоты (минимум 2 на телефон, желательно и на планшет, раз заявлена адаптивная вёрстка), feature graphic 1024×500.
- Короткое и полное описание — с явным неаффилированным статусом от Telegram, честным описанием ограничений (VPN-режим — только UDP через SOCKS5, см. раздел 5.10) вместо маркетинговых преувеличений.
- Категория — скорее всего "Инструменты" (Tools) или "Связь" (Communication), не "VPN" как основную категорию, если VPN — вспомогательная функция, а не основное назначение (иначе применяется более строгий набор VPN-специфичных требований Google целиком, а не только VPN Service declaration).

### 7.6 Реалистичный таймлайн

Для новой команды разработчиков (аккаунт < 1 года, первая публикация) с приложением, включающим `VpnService`:
- Настройка аккаунта, форм, privacy policy — 1–2 дня.
- Closed testing период — минимум 14 дней (жёсткое требование Google, не сокращается).
- Ревью production-релиза — обычно 1–3 дня, но с `VpnService` и категорией "прокси" не исключено ручное ревью до 1–2 недель, особенно при первой публикации приложения такого типа с этого аккаунта разработчика.

Итого от готового APK до публикации в проде реалистично закладывать **3–5 недель**, а не несколько дней — это специфика именно категории (прокси + VPN), а не общее правило для всех Android-приложений.

