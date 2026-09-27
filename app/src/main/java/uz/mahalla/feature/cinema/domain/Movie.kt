package uz.mahalla.feature.cinema.domain

import androidx.compose.runtime.Immutable
import java.time.LocalDate

/**
 * Фильм афиши (эпик #13, issue #106).
 *
 * Приезжает из `GET /api/v1/cinema/movies` — схема `Movie`. Имя в
 * `/v3/api-docs` встречается один раз, коллизии springdoc здесь нет, поэтому
 * поля прочитаны как есть.
 *
 * @param title название на языке, который завёл кинотеатр, [titleUz] — то же
 * по-узбекски. Какое из двух показать, решает [displayTitle]: язык приложение
 * выбирает своё (эпик 1.5), и подставлять узбекское название человеку с
 * русским интерфейсом незачем — как и наоборот.
 * @param ageRating возрастное ограничение (`rating` в схеме — **строка**,
 * то есть это `16+`, а не оценка зрителей). Оценок у фильма контракт не
 * отдаёт вовсе.
 * @param placeId кинотеатр, который завёл фильм. Нужен затем, что ручка
 * афиши **общая на всю платформу**: фильтра по заведению у неё нет
 * (`GET cinema/movies` не принимает ни одного параметра — проверено), и
 * афишу конкретного кинотеатра приложение собирает само (см. [CinemaPoster]).
 * @param isActive снятый с проката фильм в афише не показывается. `null` от
 * сервера читается как «идёт»: прятать фильм из-за неприехавшего флага хуже,
 * чем показать лишний — сеансов у снятого всё равно не будет.
 */
@Immutable
data class Movie(
    val id: String,
    val title: String,
    val titleUz: String? = null,
    val description: String? = null,
    val genre: String? = null,
    val durationMinutes: Int? = null,
    val releaseDate: LocalDate? = null,
    val posterUrl: String? = null,
    val trailerUrl: String? = null,
    val ageRating: String? = null,
    val placeId: String? = null,
    val isActive: Boolean = true,
) {

    /**
     * Название на языке интерфейса.
     *
     * Пустая строка названием не считается: если своего варианта нет, берётся
     * второй — фильм без названия в афише не опознать вовсе.
     */
    fun displayTitle(preferUzbek: Boolean): String {
        val uz = titleUz?.trim().orEmpty()
        val original = title.trim()
        return when {
            preferUzbek && uz.isNotEmpty() -> uz
            original.isNotEmpty() -> original
            else -> uz
        }
    }
}

/**
 * Афиша **этого** кинотеатра из общей афиши платформы.
 *
 * Фильм без [Movie.placeId] остаётся: молчание сервера о заведении — не повод
 * спрятать фильм из всех афиш сразу. Цена ошибки несимметрична: лишний фильм
 * кончается экраном «сеансов на этот день нет» (расписание всё равно берётся
 * у конкретного кинотеатра), а спрятанный — пустой афишей при живом прокате.
 */
object CinemaPoster {

    fun forPlace(movies: List<Movie>, placeId: String): List<Movie> = movies.filter { movie ->
        movie.isActive && (movie.placeId.isNullOrBlank() || movie.placeId == placeId)
    }
}

/**
 * Проверка ссылки на трейлер (issue #183).
 *
 * Ссылку присылает сервер, а адрес сервера в debug-сборке вводит пользователь
 * (issue #26) — без проверки подменённый бэкенд запускал бы на устройстве
 * произвольный intent (`market://`, `intent://`, чужой deep link, в том числе
 * наш `mahalla://`). То же правило, что у ссылки на бота (issue #46), на
 * магазин (issue #80) и на форму оплаты (issue #90).
 *
 * Только `https`, на любой хост: трейлер может лежать на YouTube, Vimeo или
 * своём сервере кинотеатра, а открывает его браузер или то приложение,
 * которое зарегистрировано на этот хост как App Link (YouTube — сам, без
 * отдельной схемы `vnd.youtube:`, которую сервер и не присылает).
 *
 * Разбор ручной, без `android.net.Uri`: правило проверяется JVM-тестом, а
 * `Uri` в юнит-тестах заглушен и молча вернул бы `null` у каждого поля — тест
 * был бы зелёным при любой реализации.
 */
object TrailerLink {

    private const val HTTPS = "https://"

    fun sanitize(url: String?): String? {
        val candidate = url?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        if (!candidate.startsWith(HTTPS, ignoreCase = true)) return null
        val host = candidate.substring(HTTPS.length)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        if (host.isEmpty() || candidate.any(Char::isWhitespace)) return null
        return candidate
    }
}
