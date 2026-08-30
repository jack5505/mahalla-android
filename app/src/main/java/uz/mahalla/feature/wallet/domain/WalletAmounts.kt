package uz.mahalla.feature.wallet.domain

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Перевод денег бэкенда в целые сумы (issue #62).
 *
 * Бэкенд отдаёт каждую сумму дважды: целым числом (`balance`, `amount`) и
 * дробным «в сумах» (`balanceSom`, `amountSom`). Единица целого поля в схеме
 * стенда **не описана**, а проверить её живым ответом нельзя — `GET /wallet`
 * требует входа, которого в CI нет. Гадать нельзя: ошибка в сто раз на экране
 * баланса — худшее, что может показать кошелёк.
 *
 * Поэтому масштаб не зашит, а **выводится из самой пары**: сервер уже сказал,
 * сколько это в сумах, и остаётся выбрать делитель, при котором целое поле
 * сходится с дробным. Кандидатов два — 1 (сумы) и 100 (тийины). Найденный
 * делитель применяется ко **всем** суммам того же ответа, включая те, у
 * которых дробного близнеца нет (`heldAmount`, `availableBalance`,
 * `balanceAfter`), — иначе «на счету» и «доступно» разъехались бы в сто раз
 * внутри одной карточки.
 *
 * Пары нет (ноль, отсутствующее поле) — берётся [TIYIN_IN_SOM]: наличие
 * отдельного поля `*Som` означает, что целое поле хранит что-то другое.
 */
object WalletAmounts {

    /** 1 сум = 100 тийинов. */
    const val TIYIN_IN_SOM = 100L

    private val CANDIDATES = listOf(1L, TIYIN_IN_SOM)

    /**
     * Делитель, переводящий целые суммы ответа в сумы.
     *
     * @param minor целое поле (`balance`, `amount`).
     * @param som его дробный близнец (`balanceSom`, `amountSom`).
     */
    fun scaleOf(minor: Long?, som: Double?): Long {
        if (minor == null || som == null || minor == 0L || som == 0.0) return TIYIN_IN_SOM
        // Знак не важен: сравниваются величины, а направление операции живёт
        // отдельным полем.
        val amount = abs(minor).toDouble()
        val expected = abs(som)
        return CANDIDATES.minByOrNull { abs(amount / it - expected) } ?: TIYIN_IN_SOM
    }

    /** Сумма в сумах по найденному [scaleOf] делителю. */
    fun toSom(minor: Long?, scale: Long): Long {
        if (minor == null) return 0
        if (scale <= 1L) return minor
        return (minor.toDouble() / scale).roundToLong()
    }
}
