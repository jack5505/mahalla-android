package uz.mahalla.feature.social.domain

import java.time.Instant

/**
 * Лайк, «Избранное» и счётчик лайков места (контроллер `social`, issue #75).
 *
 * Одно состояние на обе кнопки: сервер отдаёт их одним ответом
 * (`GET places/{id}/status`), и разносить их по двум полям экрана значило бы
 * держать два источника правды об одном и том же.
 */
data class PlaceSocialStatus(
    val liked: Boolean = false,
    val saved: Boolean = false,
    val likes: Long = 0,
) {

    /**
     * Оптимистичный переворот лайка: сердечко обязано откликнуться на нажатие
     * сразу, иначе на медленной сети оно «залипает» и человек жмёт второй раз.
     * Ответ сервера потом заменит счётчик своим ([withLike]).
     */
    fun toggledLike(): PlaceSocialStatus = withLike(!liked)

    /**
     * @param likes счётчик сервера. `null` — считаем сами: ±1 от текущего.
     * Повторное применение того же значения счётчик не двигает, поэтому
     * подтверждение уже применённого лайка не превращает 1 в 2.
     */
    fun withLike(liked: Boolean, likes: Long? = null): PlaceSocialStatus = normalized(
        copy(
            liked = liked,
            likes = likes ?: when {
                liked == this.liked -> this.likes
                liked -> this.likes + 1
                else -> this.likes - 1
            },
        ),
    )

    fun toggledSave(): PlaceSocialStatus = copy(saved = !saved)

    fun withSaved(saved: Boolean): PlaceSocialStatus = copy(saved = saved)

    companion object {

        /** Ответ сервера — тоже через нормализацию: считает его не клиент. */
        fun of(liked: Boolean, saved: Boolean, likes: Long): PlaceSocialStatus =
            normalized(PlaceSocialStatus(liked = liked, saved = saved, likes = likes))

        /**
         * Отрицательный счётчик и «мой лайк есть, а лайков ноль» —
         * арифметически невозможные состояния. Приехать они могут и от
         * сервера (гонка двух устройств), и от отката неудачного запроса;
         * показывать «−1 лайк» человеку незачем.
         */
        private fun normalized(status: PlaceSocialStatus): PlaceSocialStatus = status.copy(
            likes = when {
                status.likes < 0 -> 0
                status.liked && status.likes == 0L -> 1
                else -> status.likes
            },
        )
    }
}

/**
 * Комментарий к месту (`CommentResponse`).
 *
 * Имени автора в контракте нет — только `userId`, поэтому подпись под
 * комментарием у чужих записей общая. [isMine] считается сравнением с id
 * вошедшего: удалять бэкенд разрешает только свои, и предлагать кнопку там,
 * где она заведомо ответит отказом, нельзя.
 */
data class PlaceComment(
    val id: String,
    val authorId: String? = null,
    val text: String,
    val createdAt: Instant? = null,
    val isMine: Boolean = false,
)

/**
 * Страница комментариев. [hasMore] считается по `last`, а при его отсутствии —
 * по `page`/`totalPages`: полного молчания сервера о страницах достаточно,
 * чтобы остановиться, иначе экран догружал бы одну и ту же страницу в цикле.
 */
data class PlaceCommentPage(
    val items: List<PlaceComment> = emptyList(),
    val hasMore: Boolean = false,
)

/**
 * Правила черновика комментария. Длину бэкенд не объявляет (в схеме тело —
 * `Map<String,String>` без ограничений), поэтому предел клиентский: он не
 * защищает сервер, а не даёт отправить текст, который заведомо не прочитают.
 */
object CommentRules {

    const val MAX_LENGTH = 1000

    /** Хвостовые пробелы и переводы строк на сервер не уходят. */
    fun normalize(text: String): String = text.trim()

    fun canSubmit(text: String): Boolean = normalize(text).let {
        it.isNotEmpty() && it.length <= MAX_LENGTH
    }
}

/**
 * Страница «Избранного». Бэкенд отдаёт только идентификаторы
 * (`PageResponseUUID`), карточки приложение собирает само — см.
 * `SocialRepository.savedPlaces`.
 */
data class SavedPlaceIdsPage(
    val ids: List<String> = emptyList(),
    val hasMore: Boolean = false,
)
