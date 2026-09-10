package uz.mahalla.feature.security.data

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uz.mahalla.feature.security.domain.AppLockManager

/**
 * Кто сообщает замку о фоне (issue #102).
 *
 * `ProcessLifecycleOwner`, а не колбэки Activity: он один отличает настоящий
 * уход в фон от пересоздания Activity, а приложение пересоздаёт её на смене
 * языка (эпик 1.5) и темы. Своя реализация поверх
 * `registerActivityLifecycleCallbacks` запирала бы человека каждый раз, когда
 * он переключает язык в профиле.
 *
 * Отдельный класс, а не код в `MahallaApplication`: подписка на жизненный цикл
 * — это поведение, и его надо уметь завести в тесте без Android.
 */
@Singleton
class AppLockObserver @Inject constructor(
    private val appLockManager: AppLockManager,
) : DefaultLifecycleObserver {

    /**
     * Своя область, а не `viewModelScope` или `lifecycleScope`: наблюдатель
     * живёт столько же, сколько процесс, и переживает любую Activity.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        appLockManager.onBackground()
    }

    /**
     * `onStart`, а не `onResume`: это самая ранняя точка, где решение о замке
     * ещё имеет смысл принимать.
     *
     * Мгновенным оно всё равно не будет: `isArmed()` спрашивает DataStore и
     * Keystore, то есть прежний экран успевает нарисоваться на эти кадры.
     * Закрыть зазор совсем можно только кэшем «вооружён» в памяти — но кэш,
     * разошедшийся с хранилищем, показывает замок, который открыть нечем, а
     * это дороже подсмотренного кадра.
     */
    override fun onStart(owner: LifecycleOwner) {
        scope.launch { appLockManager.onForeground() }
    }
}
