package uz.mahalla.core.crash

import android.content.Context
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import uz.mahalla.core.result.runCatchingCancellable

/**
 * Отчёты о падениях на Sentry (issue #74).
 *
 * Тонкая обёртка вокруг статики SDK: всё, что можно проверить тестом,
 * вынесено — решение «слать или нет» в [CrashReportingConfig], вычистка
 * секретов в [CrashScrubber] и [SecretScrubber].
 */
class SentryCrashReporter(
    private val context: Context,
    private val config: CrashReportingConfig,
) : CrashReporter {

    override val isEnabled: Boolean = config.isEnabled

    @Volatile
    private var installed = false

    /**
     * Инициализация идёт из `MahallaApplication`, а не из `ContentProvider`
     * Sentry (он выключен в манифесте): DSN приходит из секрета сборки, в debug
     * сбор по умолчанию не нужен, а событие обязано пройти через [CrashScrubber].
     *
     * Отказ SDK не роняет приложение: смысл задачи в том, чтобы **видеть**
     * падения, а не добавить ещё одно на старте.
     */
    override fun install() {
        if (!isEnabled || installed) return
        installed = true
        runCatchingCancellable {
            SentryAndroid.init(context) { options ->
                options.dsn = config.dsn
                options.environment = config.environment
                options.release = config.release
                // Отладочный лог самого SDK не нужен даже в debug: он шумит в
                // logcat на каждое событие.
                options.setDebug(false)

                // Ничего, что указывает на человека: ни IP, ни имени
                // устройства-владельца. Отчёт нужен, чтобы починить код.
                options.isSendDefaultPii = false
                options.isAttachServerName = false

                // Скриншот и дерево вью — прямая утечка: экраны PIN, кода из
                // SMS и кошелька попали бы в панель картинкой. Значение по
                // умолчанию тоже false, но здесь это осознанный запрет.
                options.isAttachScreenshot = false
                options.isAttachViewHierarchy = false

                // Трассировок производительности задача не просит, а объём
                // трафика они дают заметный.
                options.tracesSampleRate = 0.0

                // Единственный шлюз наружу: и падения, и «хлебные крошки»
                // проходят вычистку. Ошибка внутри неё не должна отменять
                // отправку — иначе один неудачный разбор прячет падение.
                options.setBeforeSend { event, _ ->
                    runCatchingCancellable { CrashScrubber.scrub(event) }.getOrDefault(event)
                }
                options.setBeforeBreadcrumb { breadcrumb, _ ->
                    runCatchingCancellable { CrashScrubber.scrub(breadcrumb) }
                        .getOrDefault(breadcrumb)
                }
            }
        }
    }

    override fun recordNonFatal(error: Throwable, operation: String) {
        if (!isEnabled) return
        runCatchingCancellable {
            Sentry.captureException(error) { scope ->
                // Не падение, а проглоченный отказ: в панели он не должен
                // стоять рядом с крашем.
                scope.level = SentryLevel.WARNING
                scope.setTag(TAG_OPERATION, operation)
            }
        }
    }

    private companion object {
        const val TAG_OPERATION = "operation"
    }
}
