package uz.mahalla.feature.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import uz.mahalla.R
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.components.MahallaComponentDefaults
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaIconButton
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.colors
import uz.mahalla.core.ui.userMessage
import uz.mahalla.ui.theme.FocusHeadline
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Общий каркас шагов онбординга (эпик 3): шапка шага сверху, контент в
 * прокрутке, кнопки прижаты к низу.
 *
 * Один каркас на шесть экранов, потому что все они устроены одинаково, и
 * расхождение отступов между ними — самая заметная глазом ошибка вёрстки.
 * `imePadding` обязателен: на экранах телефона и кода клавиатура иначе
 * накрывает кнопку.
 *
 * Отступ под системную навигацию здесь не добавляется: `Scaffold` в
 * `MahallaApp` уже отдаёт его в `innerPadding`, и второй `navigationBarsPadding`
 * поднимал бы кнопки на высоту навбара — а с открытой клавиатурой ещё и
 * подвешивал бы футер над ней.
 *
 * @param stepLabel «Шаг 2 из 5» — счётчик из макета
 * (`design_handoff_mahalla_focus/README.md`, шаги 0b–0e). Строку собирает
 * вызывающий экран: только он знает свой номер, а порядок шагов задаёт граф
 * навигации, а не каркас.
 */
@Composable
fun OnboardingStep(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    stepLabel: String? = null,
    onBack: (() -> Unit)? = null,
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // Заголовок шага живёт в теле экрана — в шапке остаются только «назад»
        // и счётчик, иначе название дублируется и читается TalkBack дважды.
        if (onBack != null || stepLabel != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MahallaComponentDefaults.minTouchTarget)
                    .padding(horizontal = HeaderPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    MahallaIconButton(
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        onClick = onBack,
                    )
                }
                if (stepLabel != null) {
                    Text(
                        text = stepLabel,
                        // Без кнопки «назад» счётчик встаёт по полю экрана, с
                        // ней — сразу за стрелкой.
                        modifier = if (onBack == null) {
                            Modifier.padding(start = Spacing.onboardingGutter - HeaderPadding)
                        } else {
                            Modifier
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = LocalMahallaColors.current.fgMuted,
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.onboardingGutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = FocusHeadline,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalMahallaColors.current.fgMuted,
                )
            }
            content()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.onboardingGutter, vertical = Spacing.gap),
            verticalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            footer()
        }
    }
}

/**
 * Поле шапки меньше поля экрана ровно на внутренний отступ круглой кнопки
 * (48dp кнопка вокруг 24dp иконки): так стрелка встаёт по той же вертикали,
 * что и заголовок под ней.
 */
private val HeaderPadding = 12.dp

/**
 * Ошибка шага под полем ввода. `liveRegion` — TalkBack проговаривает
 * появившуюся ошибку сам, без повторного обхода экрана.
 */
@Composable
fun OnboardingError(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * Пояснение на шаге онбординга: не ошибка, но и не проходная подпись — то, без
 * чего человек застрянет. Первый случай — «код ушёл не в SMS, а в Telegram»
 * (issue #54).
 *
 * Выделено фоном тона [MahallaTone.Info], потому что обычным абзацем под
 * заголовком это уже не читается: экран кода человек открывает, чтобы найти
 * поле ввода, и текст рядом с ним пропускает.
 *
 * `mergeDescendants` — TalkBack читает блок одной репликой, а не по абзацу.
 */
@Composable
fun OnboardingNotice(
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val tone = MahallaTone.Info.colors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(tone.container)
            .padding(Spacing.card)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = tone.content,
        )
        action?.invoke(this)
    }
}

/**
 * Ошибка запроса на шаге онбординга (issue #34): текст от бэкенда (или общий,
 * если бэкенд промолчал) плюс раскрываемые подробности ответа.
 *
 * Вход и регистрация — место, где непонятная ошибка стоит дороже всего:
 * человек ещё не в приложении и не может ни пожаловаться, ни посмотреть
 * инспектор трафика в профиле.
 *
 * [showMessage] выключается там, где тот же текст уже показан подписью поля:
 * на экране кода сообщение сервера подставляется под ячейки, и повторять его
 * вторым абзацем незачем — подробности при этом остаются доступны.
 *
 * [action] — слот под кнопку, которой отказ лечится на месте (обычно
 * «Повторить»). Он нужен там, где экран без этого запроса неполон: на экране
 * безопасности (issue #102) отказ `pin/status` не прячет экран, но без
 * повтора строка «PIN-код установлен» не появится до следующего захода.
 * Слот, а не своя копия блока: третья вариация «текст + подробности + кнопка»
 * разъезжается с первыми двумя при первой же правке.
 */
@Composable
fun OnboardingApiError(
    failure: ApiFailure,
    modifier: Modifier = Modifier,
    showMessage: Boolean = true,
    action: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        if (showMessage) OnboardingError(failure.userMessage())
        failure.server?.let { MahallaErrorDetails(server = it) }
        action?.invoke(this)
    }
}
