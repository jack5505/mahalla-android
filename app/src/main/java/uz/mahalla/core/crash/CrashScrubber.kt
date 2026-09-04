package uz.mahalla.core.crash

import io.sentry.Breadcrumb
import io.sentry.SentryEvent

/**
 * Последний шаг перед отправкой отчёта (issue #74): событие Sentry проходит
 * через эти функции, и только потом уходит в сеть.
 *
 * Ставится в `beforeSend`/`beforeBreadcrumb`, то есть накрывает **всё**, что
 * собрал SDK, включая то, что положили его собственные интеграции, а не только
 * наши вызовы. Требование T8 «в отчёте нет токенов» иначе держалось бы на
 * дисциплине вызывающих.
 *
 * Работает на типах Sentry, но без Android — поэтому проверяется обычным
 * JVM-тестом на настоящих объектах SDK, а не на своей копии моделей.
 */
object CrashScrubber {

    /**
     * @return то же событие с вычищенными полями. `null` не возвращается
     * никогда: отбрасывать отчёт — не задача вычистки, иначе падение молча
     * пропадало бы из панели.
     */
    fun scrub(event: SentryEvent): SentryEvent {
        event.message?.let { message ->
            message.message = SecretScrubber.scrubText(message.message)
            message.formatted = SecretScrubber.scrubText(message.formatted)
            message.params = message.params?.map { SecretScrubber.scrubText(it).orEmpty() }
        }

        // Текст исключения пишет тот, кто его бросил: HttpException кладёт туда
        // URL, кое-где — заголовок. Стек не трогаем, в нём только имена классов.
        event.exceptions?.forEach { exception ->
            exception.value = SecretScrubber.scrubText(exception.value)
        }

        event.request?.let { request ->
            request.url = SecretScrubber.scrubUrl(request.url)
            request.queryString = SecretScrubber.scrubQuery(request.queryString)
            request.headers = SecretScrubber.scrubMap(request.headers)
            request.envs = SecretScrubber.scrubMap(request.envs)
            request.others = SecretScrubber.scrubMap(request.others)
            // Тело запроса и ответа не отправляется никогда, даже вычищенным: по
            // issue #34 бэкенд кладёт в ответ произвольный текст, и что там
            // окажется завтра, клиент знать не может.
            request.data = null
            request.cookies = null
        }

        event.tags = SecretScrubber.scrubMap(event.tags)
        event.extras = event.extras?.mapValues { (name, value) ->
            scrubValue(name, value)
        }

        // Пользователя приложение не сообщает (см. CrashReporter), а IP-адрес
        // Sentry подставляет сам, когда включён sendDefaultPii. Здесь второй
        // замок на ту же дверь: отчёт о падении не должен указывать на человека.
        event.user?.ipAddress = null

        event.breadcrumbs = event.breadcrumbs?.map { breadcrumb -> scrub(breadcrumb) }
        return event
    }

    /**
     * @return вычищенная «хлебная крошка» — короткая запись о том, что было
     * перед падением (переход экрана, смена сети, HTTP-запрос).
     */
    fun scrub(breadcrumb: Breadcrumb): Breadcrumb {
        breadcrumb.message = SecretScrubber.scrubText(breadcrumb.message)
        val data = breadcrumb.data
        // Копия ключей: карта правится во время обхода.
        data.keys.toList().forEach { key ->
            data[key]?.let { value -> breadcrumb.setData(key, scrubValue(key, value)) }
        }
        return breadcrumb
    }

    /**
     * Значение произвольного типа: секретное по имени — вырезается целиком,
     * строка — чистится, число или флаг остаются как есть (секрета в них нет, а
     * тип поля ломать нельзя — Sentry сериализует его по факту).
     */
    private fun scrubValue(name: String, value: Any): Any = when {
        SecretScrubber.isSecretName(name) -> SecretScrubber.REDACTED
        value is String -> SecretScrubber.scrubText(value).orEmpty()
        else -> value
    }
}
