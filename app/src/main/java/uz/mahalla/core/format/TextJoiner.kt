package uz.mahalla.core.format

/**
 * Склейка непустых частей строки через шаблон вида `"%1$s · %2$s"`
 * (`R.string.text_joined_with_dot`) — разделитель берётся из ресурса, а не
 * зашивается символом в код экрана (issue #212). Шаблон резолвит вызывающая
 * сторона: тот же приём, что у [MoneyFormatter.withCurrency] с подписью валюты.
 */
object TextJoiner {

    /** `null` и пустые/пробельные части выбрасывает; ноль частей даёт `""`. */
    fun join(template: String, parts: List<String?>): String =
        parts.filterNot { it.isNullOrBlank() }
            .reduceOrNull { joined, part -> template.format(joined, part) }
            .orEmpty()

    fun join(template: String, vararg parts: String?): String = join(template, parts.toList())
}
