import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Ключ Yandex MapKit (эпик 4.2). В репозиторий он не попадает — берётся из
 * переменной окружения `MAPKIT_API_KEY` (CI) или из `local.properties`,
 * строка `mapkit.apiKey=…` (машина разработчика; файл в .gitignore).
 *
 * Пустое значение — не ошибка сборки: без ключа приложение собирается и
 * работает, а на месте карты показывается объяснение (см. `MapKitInitializer`).
 * Иначе один незаполненный секрет ронял бы сборку всем.
 */
fun mapkitApiKey(): String {
    // providers.*, а не System.getenv/File.readText: иначе значение читается в
    // обход Gradle, и configuration cache не пересобирается при смене ключа.
    val fromEnvironment = providers.environmentVariable("MAPKIT_API_KEY").orNull
    if (!fromEnvironment.isNullOrBlank()) return fromEnvironment.trim()

    val localProperties = providers.fileContents(
        rootProject.layout.projectDirectory.file("local.properties"),
    ).asText.orNull ?: return ""

    val properties = Properties().apply { load(localProperties.reader()) }
    return properties.getProperty("mapkit.apiKey").orEmpty().trim()
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
        // uz — язык по умолчанию (values/), ru — values-ru/. Список локалей для
        // per-app languages (API 33+) лежит в res/xml/locales_config.xml.
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
            buildConfigField("String", "API_BASE_URL", "\"https://189-74-96-232.nip.io/api/v1/\"")
            // Адрес бэкенда меняется прямо в приложении (issue #26).
            buildConfigField("boolean", "BACKEND_URL_OVERRIDE", "true")
        }
        getByName("release") {
            isMinifyEnabled = false
            buildConfigField("String", "API_BASE_URL", "\"https://api.mahalla.uz/api/v1/\"")
            // Экран адреса в релизе спрятан, пока сборку не попросили обратное:
            // иначе увести приложение на чужой сервер может кто угодно.
            buildConfigField(
                "boolean",
                "BACKEND_URL_OVERRIDE",
                backendUrlOverrideEnabled().toString(),
            )
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    // BiometricPrompt требует FragmentActivity — fragment приходит транзитивно,
    // поэтому MainActivity наследуется от неё (эпик 3.5).
    implementation(libs.androidx.biometric)
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
}
