package uz.mahalla

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Раннер инструментальных тестов (issue #347): `HiltAndroidRule` собирает
 * тестовый граф Hilt, но только если приложение — `HiltTestApplication`, а не
 * [MahallaApplication] из манифеста. Подмена — здесь, до первого
 * `Application.onCreate()`.
 */
class HiltTestRunner : AndroidJUnitRunner() {

    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
