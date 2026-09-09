import org.gradle.api.tasks.PathSensitivity
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Скриншот-тесты темы (эпик 13.2): задачи recordRoborazziDebug /
    // verifyRoborazziDebug / compareRoborazziDebug.
    alias(libs.plugins.roborazzi)
    // Baseline Profile (эпик 13.3): плагин связывает :app с модулем
    // :baselineprofile, который снимает профиль на устройстве.
    alias(libs.plugins.baselineprofile)
}

/**
 * Значение, которое нельзя держать в репозитории: переменная окружения
 * (секрет Actions) или строка в `local.properties` на машине разработчика
 * (файл в .gitignore).
 *
 * providers.*, а не System.getenv/File.readText: иначе значение читается в
 * обход Gradle, и configuration cache не пересобирается при смене ключа.
 *
 * Пустое значение — не ошибка сборки ни для одного из вызовов ниже: иначе
 * один незаполненный секрет ронял бы сборку всем, включая форки.
 */
fun secret(environmentName: String, localPropertyName: String): String {
    val fromEnvironment = providers.environmentVariable(environmentName).orNull
    if (!fromEnvironment.isNullOrBlank()) return fromEnvironment.trim()

    val localProperties = providers.fileContents(
        rootProject.layout.projectDirectory.file("local.properties"),
    ).asText.orNull ?: return ""

    val properties = Properties().apply { load(localProperties.reader()) }
    return properties.getProperty(localPropertyName).orEmpty().trim()
}

/**
 * Ключ Yandex MapKit (эпик 4.2). `MAPKIT_API_KEY` или `mapkit.apiKey`.
 *
 * Без ключа приложение собирается и работает, а на месте карты показывается
 * объяснение (см. `MapKitInitializer`).
 */
fun mapkitApiKey(): String = secret("MAPKIT_API_KEY", "mapkit.apiKey")

/**
 * DSN Sentry (issue #74) — адрес проекта, куда уезжают отчёты о падениях.
 * `SENTRY_DSN` или `sentry.dsn`. В репозиторий не кладётся: DSN — это право
 * писать в чужой проект.
 *
 * Без DSN приложение собирается и работает, просто отчёты никуда не уходят
 * (см. `CrashReportingConfig`).
 */
fun sentryDsn(): String = secret("SENTRY_DSN", "sentry.dsn")

/**
 * Слать ли отчёты из debug-сборки (issue #74).
 *
 * По умолчанию нет: падение на машине разработчика — это работа, а не
 * инцидент, и панель от них засоряется так, что настоящие падения в ней
 * теряются. Включается на время отладки самого сбора:
 * `SENTRY_ENABLED_IN_DEBUG=true` (переменная окружения или `-P`).
 */
fun sentryEnabledInDebug(): Boolean {
    val fromEnvironment = providers.environmentVariable("SENTRY_ENABLED_IN_DEBUG").orNull
    val fromProperty = providers.gradleProperty("SENTRY_ENABLED_IN_DEBUG").orNull
    return (fromEnvironment ?: fromProperty).orEmpty().trim().equals("true", ignoreCase = true)
}

/**
 * Разрешено ли сборке менять адрес бэкенда прямо в приложении (issue #26).
 *
 * В debug — всегда: разработчик и тестировщик каждый день ходят на свой стенд.
 * В release — только если сборку собрали с `BACKEND_URL_OVERRIDE=true`
 * (переменная окружения или `-PBACKEND_URL_OVERRIDE=true`): в магазинной
 * сборке экран адреса увёл бы приложение любого пользователя на чужой сервер.
 */
fun backendUrlOverrideEnabled(): Boolean {
    val fromEnvironment = providers.environmentVariable("BACKEND_URL_OVERRIDE").orNull
    val fromProperty = providers.gradleProperty("BACKEND_URL_OVERRIDE").orNull
    return (fromEnvironment ?: fromProperty).orEmpty().trim().equals("true", ignoreCase = true)
}

/** Строковый литерал для `buildConfigField`: ключ едет в генерируемый .java. */
fun stringLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * Адрес бэкенда для окружения (эпик 13.4). Значение по умолчанию зашито в
 * buildType ниже, но сборку можно направить на другой стенд, не трогая код:
 * `API_BASE_URL_DEBUG` / `API_BASE_URL_RELEASE` (переменная окружения или
 * `-P`). Это нужно ровно для preview-стендов и релиз-кандидатов: заводить
 * ради каждого стенда отдельный productFlavor — умножать варианты сборки.
 *
 * Адрес обязан оканчиваться на `/`: Retrofit молча отбрасывает последний
 * сегмент baseUrl без слэша, и все запросы уезжают на уровень выше.
 */
fun apiBaseUrl(environmentName: String, default: String): String {
    // Пустая переменная окружения не должна перебивать `-P`: в CI переменные
    // объявляют заранее, а значение подставляют не всегда.
    val fromEnvironment = providers.environmentVariable(environmentName).orNull?.takeIf(String::isNotBlank)
    val fromProperty = providers.gradleProperty(environmentName).orNull
    val override = (fromEnvironment ?: fromProperty).orEmpty().trim()
    if (override.isEmpty()) return default
    return if (override.endsWith("/")) override else "$override/"
}

/**
 * Подпись release-сборки (эпик 13.4). Хранилище ключей в репозиторий не
 * кладётся: `MAHALLA_KEYSTORE_FILE` + `MAHALLA_KEYSTORE_PASSWORD` +
 * `MAHALLA_KEY_ALIAS` + `MAHALLA_KEY_PASSWORD` (секреты Actions) либо те же
 * значения в `local.properties` как `release.keystore.file` и далее.
 *
 * Путь — абсолютный или относительно корня проекта. Хранилища нет — release
 * собирается неподписанным (`assembleRelease` в CI проверяет R8, а не
 * выкладку), и об этом печатается предупреждение, чтобы неподписанный APK не
 * уехал в магазин молча.
 */
fun releaseKeystore(): ReleaseKeystore? {
    val path = secret("MAHALLA_KEYSTORE_FILE", "release.keystore.file")
    if (path.isEmpty()) return null

    val keystoreFile = rootProject.layout.projectDirectory.file(path).asFile
    if (!keystoreFile.exists()) {
        logger.warn("Release keystore не найден: $keystoreFile — release будет неподписанным.")
        return null
    }
    val keystore = ReleaseKeystore(
        file = keystoreFile,
        storePassword = secret("MAHALLA_KEYSTORE_PASSWORD", "release.keystore.password"),
        keyAlias = secret("MAHALLA_KEY_ALIAS", "release.key.alias"),
        keyPassword = secret("MAHALLA_KEY_PASSWORD", "release.key.password"),
    )
    // Хранилище задали, а пароль или алиас забыли — падаем здесь и по-русски,
    // а не в недрах apksigner на «keystore password was incorrect».
    require(
        keystore.storePassword.isNotEmpty() &&
            keystore.keyAlias.isNotEmpty() &&
            keystore.keyPassword.isNotEmpty(),
    ) {
        "Задан MAHALLA_KEYSTORE_FILE, но не заданы MAHALLA_KEYSTORE_PASSWORD, " +
            "MAHALLA_KEY_ALIAS или MAHALLA_KEY_PASSWORD (см. docs/RELEASE.md)."
    }
    return keystore
}

data class ReleaseKeystore(
    val file: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

android {
    namespace = "uz.mahalla"
    compileSdk = 35

    defaultConfig {
        applicationId = "uz.mahalla"
        // ТЗ (design/android/TZ-ANDROID.md): minSdk 26, только portrait.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "MAPKIT_API_KEY", stringLiteral(mapkitApiKey()))
        buildConfigField("String", "SENTRY_DSN", stringLiteral(sentryDsn()))
        // uz — язык по умолчанию (values/), ru — values-ru/. Список локалей для
        // per-app languages (API 33+) лежит в res/xml/locales_config.xml.
    }

    // Подпись release (эпик 13.4). Конфигурация создаётся только когда
    // хранилище ключей реально есть: пустой signingConfig валит сборку на
    // `Keystore file not set`, а собирать release без ключа надо — именно так
    // CI проверяет, что R8 отработал.
    signingConfigs {
        releaseKeystore()?.let { keystore ->
            create("release") {
                // Схемы подписи (v1/v2/v3) не трогаем: AGP включает нужные по
                // minSdk, а перечислять их руками — способ однажды выключить
                // ту, без которой APK не ставится.
                storeFile = keystore.file
                storePassword = keystore.storePassword
                keyAlias = keystore.keyAlias
                keyPassword = keystore.keyPassword
            }
        }
    }

    buildTypes {
        // baseUrl задаётся buildType'ом (эпик 1.3): debug смотрит на стенд
        // разработки, release — на прод.
        getByName("debug") {
            // Стенд (issue #44): адрес подставляется в поле на экране ввода,
            // то есть в debug приложение из коробки ходит туда, куда надо, и
            // набирать URL руками не нужно.
            //
            // Домен nip.io резолвится в 189.74.96.232, и на него выписан
            // сертификат Let's Encrypt — в отличие от прежнего голого IP
            // (issue #32), доверять сертификату вручную больше не требуется.
            // Прежний адрес эмулятора (`http://10.0.2.2:8080/api/v1/`) при
            // работе с локальным бэкендом вводится на том же экране.
            //
            // Путь `api/v1/` — часть baseUrl: эндпоинты бэкенда объявлены
            // относительно него (issue #42, `auth/send-otp` и остальные).
            //
            // Стенд разработки меняется на другой через `API_BASE_URL_DEBUG`
            // (эпик 13.4) — например, когда бэкенд поднят в docker рядом.
            buildConfigField(
                "String",
                "API_BASE_URL",
                stringLiteral(
                    apiBaseUrl("API_BASE_URL_DEBUG", "https://189-74-96-232.nip.io/api/v1/"),
                ),
            )
            // Адрес бэкенда меняется прямо в приложении (issue #26).
            buildConfigField("boolean", "BACKEND_URL_OVERRIDE", "true")
            // Отчёты о падениях (issue #74): в debug — только по явному флагу.
            buildConfigField(
                "boolean",
                "CRASH_REPORTING_ENABLED",
                sentryEnabledInDebug().toString(),
            )
        }
        getByName("release") {
            // R8 (эпик 13.4): выкидывает неиспользуемый код и переименовывает
            // остальной. Правила — в proguard-rules.pro; всё, что резолвится
            // рефлексией (kotlinx.serialization, Retrofit, JNI MapKit), должно
            // быть перечислено там, иначе падение будет только в release.
            isMinifyEnabled = true
            // Ресурсы шринкуются только вместе с кодом: без minify AGP
            // отказывается включать shrinkResources.
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            buildConfigField(
                "String",
                "API_BASE_URL",
                stringLiteral(apiBaseUrl("API_BASE_URL_RELEASE", "https://api.mahalla.uz/api/v1/")),
            )
            // Экран адреса в релизе спрятан, пока сборку не попросили обратное:
            // иначе увести приложение на чужой сервер может кто угодно.
            buildConfigField(
                "boolean",
                "BACKEND_URL_OVERRIDE",
                backendUrlOverrideEnabled().toString(),
            )
            // Ради этой сборки задача и делалась: падение у пользователя иначе
            // не видно никак. Фактически включится только с непустым DSN.
            buildConfigField("boolean", "CRASH_REPORTING_ENABLED", "true")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric нужен доступ к ресурсам (DAO- и DataStore-тесты).
            isIncludeAndroidResources = true
            isReturnDefaultValues = true

            all {
                // Эталоны скриншот-тестов (эпик 13.2) — вход задачи тестов.
                // Без этой строки Gradle считает задачу актуальной, если
                // изменились только картинки, и `verifyRoborazziDebug`
                // проходит, ничего не сверив: тест, который не краснеет.
                it.inputs
                    .dir(layout.projectDirectory.dir("src/test/screenshots"))
                    .withPropertyName("roborazziGoldenImages")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    bundle {
        language {
            // Per-app languages (эпик 1.5): язык переключается внутри
            // приложения, поэтому выносить локали в отдельный split нельзя —
            // выбранный язык оказался бы не скачан, и интерфейс молча остался
            // бы на языке системы. Play Core ради этого не тянем.
            enableSplit = false
        }
    }

    lint {
        // Шаг lintDebug вернулся в CI (issue #39): каждое замечание либо
        // исправлено, либо подавлено рядом с кодом с объяснением, поэтому
        // новое предупреждение — это регресс, и сборка на нём падает.
        abortOnError = true
        warningsAsErrors = true
        disable += setOf(
            // Версии стека зафиксированы (AGENTS.md, rules/tech-stack.md):
            // AGP 8.7.3 + Gradle 8.11.1 + Kotlin 2.0.21 — проверенная связка
            // под JDK 17, обновление идёт отдельной задачей с полным прогоном.
            // Вдобавок обе проверки ходят в сеть за списком версий, то есть
            // в CI их результат зависит не от кода.
            "AndroidGradlePluginVersion",
            "GradleDependency",
            // targetSdk 35 задан ТЗ (design/android/TZ-ANDROID.md) и связан с
            // compileSdk 35; поднимать его — отдельная задача с регрессом.
            "OldTargetApi",
        )
    }
}

ksp {
    // Схемы Room (issue #64) коммитятся в app/schemas: без них миграции
    // писались бы по памяти, а сравнить две версии было бы нечем.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    // BiometricPrompt требует FragmentActivity — fragment приходит транзитивно,
    // поэтому MainActivity наследуется от неё (эпик 3.5).
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    // Chucker (issue #30): в debug — настоящая библиотека с экраном трафика,
    // в release — no-op с той же публичной поверхностью (Chucker.isOp = false).
    // Благодаря no-op код инспектора живёт в main и не ветвится по sourceSet'ам,
    // а в магазинную сборку не приезжают ни экран, ни база транзакций.
    debugImplementation(libs.chucker)
    releaseImplementation(libs.chucker.no.op)

    // Карта (эпик 4.2). Инициализация — ленивая, из MapKitInitializer.
    implementation(libs.yandex.mapkit)

    // Отчёты о падениях (issue #74). Автозапуск через ContentProvider выключен
    // в манифесте: SDK поднимается из MahallaApplication, чтобы решение «слать
    // или не слать» принималось одним местом (CrashReportingConfig), а событие
    // проходило через вычистку секретов (CrashScrubber).
    implementation(libs.sentry.android.core)

    // Картинки (issue #60). ImageLoader собирается в графе и ходит по тому же
    // OkHttp, что и остальное приложение, — см. MahallaImageLoader.
    implementation(libs.coil.compose)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // Compose-тесты под Robolectric (issue #137). Нужны там, где проверять
    // надо саму композицию: MahallaAsyncImage не грузил картинки именно
    // из-за того, как устроено дерево, — на уровне ViewModel такое не видно.
    // ui-test-manifest даёт ComponentActivity для createComposeRule; в debug,
    // потому что unit-тесты собираются из манифеста debug-варианта.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Скриншот-тесты темы (эпик 13.2). Рисуют то же дерево, что и Compose на
    // устройстве, но на JVM под Robolectric — эмулятора в CI нет.
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

    // Baseline Profile (эпик 13.3): библиотека ставит профиль из APK на
    // устройстве при первом запуске. Без неё профиль в APK лежит мёртвым
    // грузом на всех версиях Android до 9 и частично на новых.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
}
