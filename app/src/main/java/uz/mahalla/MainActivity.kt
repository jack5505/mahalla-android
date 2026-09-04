package uz.mahalla

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import uz.mahalla.core.locale.AppLanguage
import uz.mahalla.core.locale.LocaleContextWrapper
import uz.mahalla.core.locale.LocaleEntryPoint
import uz.mahalla.feature.root.ui.RootUiState
import uz.mahalla.feature.root.ui.RootViewModel
import uz.mahalla.navigation.BackendUrlRoute
import uz.mahalla.navigation.MahallaApp
import uz.mahalla.navigation.MainGraph
import uz.mahalla.navigation.OnboardingGraph
import uz.mahalla.navigation.PinRoute
import uz.mahalla.navigation.UpdateRoute
import uz.mahalla.navigation.WelcomeRoute
import uz.mahalla.ui.theme.MahallaTheme

/**
 * Единственная Activity приложения.
 *
 * Наследуется от [FragmentActivity], а не от `ComponentActivity`:
 * `BiometricPrompt` (эпик 3.5) умеет работать только с ней — внутри он
 * показывает свой фрагмент. Для Compose разницы нет, `FragmentActivity` сама
 * наследник `ComponentActivity`.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val viewModel: RootViewModel by viewModels()

    /** Пока настройки не прочитаны — держим системный splash (эпик 1.6). */
    @Volatile
    private var contentReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !contentReady }
        enableEdgeToEdge()

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()

            LaunchedEffect(state) {
                if (state is RootUiState.Ready) contentReady = true
            }

            val ready = state as? RootUiState.Ready ?: return@setContent
            // Зафиксировано во ViewModel: пересчёт на каждой эмиссии настроек
            // сбрасывал бы back stack (см. RootViewModel).
            val appStart = if (ready.startWithOnboarding) OnboardingGraph else MainGraph
            MahallaTheme(darkTheme = ready.settings.themeMode.isDark(isSystemInDarkTheme())) {
                MahallaApp(
                    // Адрес бэкенда не задан (issue #26) — до него приложение
                    // всё равно никуда не сходит, поэтому он первый: без него
                    // и версию спросить не у кого.
                    startDestination = when {
                        ready.needsBackendUrl -> BackendUrlRoute
                        // Бэкенд просит обновиться (issue #80): обязательное
                        // обновление дальше не пускает, мягкое предложение
                        // уходит по «Позже» на тот же appStart.
                        ready.showUpdate -> UpdateRoute
                        else -> appStart
                    },
                    afterBackendUrl = appStart,
                    afterUpdate = appStart,
                    backendUrlOverrideEnabled = ready.backendUrlOverrideEnabled,
                    onOnboardingFinished = viewModel::onOnboardingFinished,
                    // Вход уже пройден, а онбординг — нет: продолжаем с PIN,
                    // иначе пользователь получит второй платный SMS-код.
                    onboardingStartDestination = if (ready.resumeOnboardingAtPin) {
                        PinRoute
                    } else {
                        WelcomeRoute
                    },
                )
            }
        }
    }

    /**
     * Фолбэк per-app languages для API 26–32 (эпик 1.5): на API 33+ локаль
     * применяет система, ниже — подменяем `Context` до создания вью.
     *
     * Настройки читаются блокирующе через Hilt entry point: граф уже создан
     * (`Application.onCreate` прошёл), а UI без языка строить нельзя.
     */
    override fun attachBaseContext(newBase: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            super.attachBaseContext(newBase)
            return
        }
        super.attachBaseContext(LocaleContextWrapper.wrap(newBase, storedLanguage(newBase)))
    }

    private fun storedLanguage(context: Context): AppLanguage = runCatching {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            LocaleEntryPoint::class.java,
        )
        runBlocking { entryPoint.settingsDataStore().current().language }
    }.getOrDefault(AppLanguage.Default)
}
