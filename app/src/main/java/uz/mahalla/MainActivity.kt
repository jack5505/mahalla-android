package uz.mahalla

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import uz.mahalla.core.locale.AppLanguage
import uz.mahalla.core.locale.LocaleContextWrapper
import uz.mahalla.core.locale.LocaleEntryPoint
import uz.mahalla.feature.root.ui.RootUiState
import uz.mahalla.feature.root.ui.RootViewModel
import uz.mahalla.feature.security.ui.lock.AppLockScreen
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

    /**
     * Контроллер навигации живого экрана — нужен [onNewIntent] (эпик 11).
     *
     * Activity объявлена `singleTop`, поэтому нажатие на пуш при запущенном
     * приложении не создаёт вторую Activity, а приносит новый `Intent` сюда.
     * Первый `Intent` разбирает сам `NavHost` при построении графа (либо —
     * если старт был за гейтом, issue #160 — `MahallaApp.DeferredDeepLinkEffect`
     * тем же `handleDeepLink`, как только гейт пройден), а этот — разбирать
     * некому: композиция уже собрана.
     */
    private var navController: NavHostController? = null

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
            // Стартовый пункт — гейт (issue #160): `NavHost.setGraph` разбирает
            // `activity.intent` сам сразу после построения графа, и ссылка из
            // пуша вытеснила бы `BackendUrlRoute`/`UpdateRoute` раньше, чем
            // человек до них дошёл. `remember` без ключей — единственный раз за
            // жизнь композиции, ровно на первой эмиссии `Ready`: `needsBackendUrl`
            // и `showUpdate` дальше не меняются (см. `RootViewModel.start`).
            val pendingDeepLink = remember {
                val gated = ready.needsBackendUrl || ready.showUpdate
                val hasDeepLink = intent.data != null
                (if (gated && hasDeepLink) Intent(intent) else null).also {
                    if (gated) intent.data = null
                }
            }
            val locked by viewModel.locked.collectAsStateWithLifecycle()
            val controller = rememberNavController()
            // Ссылка держится только пока композиция жива: разобрать deep link
            // мёртвым контроллером нельзя, а `onNewIntent` приходит и после
            // того, как Activity ушла в фон.
            DisposableEffect(controller) {
                navController = controller
                onDispose { navController = null }
            }
            MahallaTheme(darkTheme = ready.settings.themeMode.isDark(isSystemInDarkTheme())) {
                MahallaApp(
                    navController = controller,
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
                    // Сессия может умереть на любом экране (issue #138):
                    // уводить на вход умеет только корень.
                    sessionExpired = viewModel.sessionExpired,
                    pendingDeepLink = pendingDeepLink,
                    // Вход уже пройден, а онбординг — нет: продолжаем с PIN,
                    // иначе пользователь получит второй платный SMS-код.
                    onboardingStartDestination = if (ready.resumeOnboardingAtPin) {
                        PinRoute
                    } else {
                        WelcomeRoute
                    },
                )

                // Замок приложения (issue #102) — оверлеем **поверх**
                // навигации, а не маршрутом: он обязан накрывать любой экран,
                // включая онбординг, обновление и ввод адреса бэкенда, и при
                // этом не трогать back stack.
                AppLockScreen(
                    locked = locked,
                    // «Забыли PIN» на экране блокировки уже выполнил выход
                    // и сбросил флаг онбординга. Просто спрятать оверлей
                    // мало: под ним остался экран, куда человека застал
                    // фон, а сессии для него больше нет. Старт графа
                    // пересчитывает `RootViewModel` — и он же снимает
                    // замок. `recreate()`, как на смене языка, здесь не
                    // годится: пересоздание сохраняет `ViewModelStore`, то
                    // есть тот же зафиксированный старт.
                    onAuthRestartRequired = viewModel::onAuthRestartRequired,
                )
            }
        }
    }

    /**
     * Прячем окно от снимка для «недавних» (issue #102).
     *
     * Снимок задачи система делает в промежутке между `onPause` и `onStop` —
     * то есть **до** того, как замок защёлкнется: он срабатывает только на
     * возврате. Без этого в списке задач оставался кошелёк с балансом, то
     * есть дырка мимо самой фичи, ради которой замок и делали.
     *
     * Флаг ставится на паузе и снимается на резюме, а не висит постоянно,
     * потому что `FLAG_SECURE` запрещает скриншот **и пока приложение на
     * экране**: талон очереди и QR — как раз то, что человек показывает и
     * сохраняет. Гейт «есть ли PIN» для этого не годится — PIN обязателен на
     * входе (токены отдаёт только `setup-pin`/`pin-login`), так что он был бы
     * истиной у всех и означал бы «запретить скриншоты навсегда».
     *
     * **Руками на устройстве не проверено** (эмулятора в CI нет): порядок
     * «onPause → снимок» описан в документации, но на отдельных прошивках
     * снимок делают раньше. Если окажется, что рано — флаг придётся вешать
     * постоянно и отдельно решать судьбу скриншотов талона.
     */
    override fun onPause() {
        super.onPause()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onResume() {
        super.onResume()
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /**
     * Нажали на пуш, когда приложение уже запущено (эпик 11).
     *
     * `setIntent` обязателен: без него `getIntent()` возвращал бы тот, с
     * которым Activity создавали, и пересозданная композиция ушла бы по старой
     * ссылке. `handleDeepLink` отвечает `false`, если ссылки в графе нет, —
     * тогда просто остаёмся на текущем экране, это лучше, чем упасть.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navController?.handleDeepLink(intent)
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
