// Модуль снятия Baseline Profile и замера холодного старта (эпик 13.3).
//
// Почему отдельный модуль: и генератор профиля, и macrobenchmark запускают
// приложение снаружи, как это делает пользователь, поэтому живут в модуле
// типа `com.android.test` и ставятся на устройство отдельным APK.
//
// Здесь ничего не запускается автоматически: и генерация, и замер требуют
// устройства или эмулятора, а их нет ни в CI, ни в песочнице агента
// (AGENTS.md). Команды — в docs/PERFORMANCE.md.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "uz.mahalla.baselineprofile"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        // Не minSdk приложения (26): снять профиль и померить старт можно
        // только начиная с API 28 — раньше в системе нет ни нужных команд
        // профилировщика, ни трассировки старта. На сборку приложения это не
        // влияет, модуль в APK не попадает.
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    testOptions.managedDevices.allDevices {
        // Эмулятор, который Gradle скачивает и поднимает сам: результат не
        // зависит от того, что подключено к машине. AOSP-образ, а не google —
        // сервисы Google в замере старта только шумят.
        create<com.android.build.api.dsl.ManagedVirtualDevice>("pixel6Api34") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "aosp"
        }
    }
}

baselineProfile {
    // Профиль снимается на управляемом эмуляторе, а не на «том, что воткнуто»:
    // иначе профиль зависит от устройства разработчика.
    managedDevices += "pixel6Api34"
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
