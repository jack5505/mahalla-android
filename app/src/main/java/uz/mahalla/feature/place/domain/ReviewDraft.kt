package uz.mahalla.feature.place.domain

import androidx.compose.runtime.Immutable

/**
 * Черновик отзыва (issue #76): что человек набрал в форме до отправки.
 *
 * Правила вынесены сюда чистыми функциями — форму нельзя проверить ни
 * скриншотом, ни запросом, а «кнопка включилась раньше времени» стоит
 * пользователю отказа сервера вместо подсказки на экране.
 *
 * @param rating 0 — оценки ещё нет. Ноль, а не `null`: сервер принимает
 * [RATING_RANGE], и «не выбрано» удобнее держать значением вне диапазона, чем
 * вторым состоянием.
 */
@Immutable
data class ReviewDraft(
    val rating: Int = NO_RATING,
    val text: String = "",
) {

    /** Пробелы по краям не отзыв: их не считаем ни длиной, ни содержанием. */
    val trimmedText: String get() = text.trim()

    val isRated: Boolean get() = rating in RATING_RANGE

    /**
     * Ограничение бэкенда — `@Size(max = 2000)`. Резать текст на вводе нельзя:
     * человек не поймёт, куда пропали набранные символы, — поэтому лишнее
     * показывается ошибкой, а отправка блокируется.
     */
    val isTooLong: Boolean get() = trimmedText.length > MAX_TEXT_LENGTH

    /** Текст необязателен: одна оценка — уже отзыв, и это нормальный случай. */
    val canSubmit: Boolean get() = isRated && !isTooLong

    /** Пустой текст уходит отсутствующим полем, а не пустой строкой. */
    fun textOrNull(): String? = trimmedText.takeIf(String::isNotBlank)

    fun withRating(value: Int): ReviewDraft = copy(rating = value)

    fun withText(value: String): ReviewDraft = copy(text = value)

    companion object {
        const val NO_RATING = 0

        /** Оценка бэкенда — целое от 1 до 5 (`@Min(1) @Max(5)`). */
        val RATING_RANGE = 1..5

        const val MAX_TEXT_LENGTH = 2000

        /**
         * Черновик не прошёл проверку на клиенте. Код тот же, каким бэкенд
         * отвечает на невалидное тело: экран показывает свою строку по
         * классификации ошибки и не выдумывает сообщение от имени сервера.
         */
        const val INVALID_CODE = "VALIDATION_ERROR"
    }
}
