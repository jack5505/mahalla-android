package uz.mahalla.feature.freelancer.ui.profile

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.CardSkeleton
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaChoiceCard
import uz.mahalla.core.ui.components.MahallaFilterChip
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.booking.domain.BarberService
import uz.mahalla.feature.booking.ui.InlineFailure
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.freelancer.domain.FreelancerOrder
import uz.mahalla.feature.freelancer.domain.FreelancerOrderDraft
import uz.mahalla.feature.freelancer.domain.FreelancerOrderStatus
import uz.mahalla.feature.freelancer.ui.catalog.FreelancerMeta
import uz.mahalla.feature.freelancer.ui.catalog.rateText
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * Профиль мастера и заказ услуги (issue #107).
 *
 * Всё на одном прокручиваемом экране — как в брони (issue #97) и у врачей
 * (issue #99): выбор услуги меняет цену, и в мастере из нескольких окон
 * человек ходил бы назад-вперёд.
 */
@Composable
fun FreelancerProfileScreen(
    onOpenMyOrders: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FreelancerProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FreelancerProfileEffect.Dial -> context.startActivitySafely(
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:${effect.phone}")),
                )

                FreelancerProfileEffect.OpenMyOrders -> onOpenMyOrders()
            }
        }
    }

    FreelancerProfileContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun FreelancerProfileContent(
    state: FreelancerProfileState,
    onEvent: (FreelancerProfileEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = state.freelancerName.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.freelancer_unnamed),
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // Адрес и комментарий набирают с клавиатуры: без этого их поля
                // оказались бы под ней вместе с кнопкой заказа.
                .imePadding()
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            // Заказ создан — выбирать больше нечего: форма уступает место
            // подтверждению. Сам экран при этом не уходит: молчаливый переход
            // читается как «ничего не произошло» (issue #49).
            val ordered = state.ordered
            if (ordered != null) {
                OrderedBlock(order = ordered, onEvent = onEvent)
                return@Column
            }

            ProfileBlock(state = state, onEvent = onEvent)

            SectionHeader(title = stringResource(R.string.freelancer_services_title))
            ServicesBlock(state = state, onEvent = onEvent)

            if (state.draft.serviceId != null) {
                SectionHeader(title = stringResource(R.string.freelancer_when_title))
                DatesRow(state = state, onEvent = onEvent)
                TimesBlock(state = state, onEvent = onEvent)

                SectionHeader(title = stringResource(R.string.freelancer_order_details_title))
                AddressField(state = state, onEvent = onEvent)
                CommentField(state = state, onEvent = onEvent)
            }

            state.orderFailure?.let { InlineFailure(failure = it) }

            if (state.draft.serviceId != null) {
                SubmitBlock(state = state, onEvent = onEvent)
            }
        }
    }
}

@Composable
private fun ProfileBlock(
    state: FreelancerProfileState,
    onEvent: (FreelancerProfileEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    when (val profile = state.profile) {
        is ScreenState.Loading -> CardSkeleton(modifier = modifier)

        // Профиль по id либо есть, либо `404` — пустым он не приходит.
        is ScreenState.Empty -> Unit

        is ScreenState.Error -> InlineFailure(
            failure = profile.failure,
            onRetry = { onEvent(FreelancerProfileEvent.ProfileRetry) },
            modifier = modifier,
        )

        is ScreenState.Content -> {
            val freelancer = profile.data
            MahallaCard(modifier = modifier) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.item),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = freelancer.name.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.freelancer_unnamed),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    MahallaBadge(
                        text = stringResource(
                            if (freelancer.isAvailable) {
                                R.string.freelancer_available
                            } else {
                                R.string.freelancer_unavailable
                            },
                        ),
                        tone = if (freelancer.isAvailable) {
                            MahallaTone.Success
                        } else {
                            MahallaTone.Neutral
                        },
                    )
                }

                freelancer.profession?.let { profession ->
                    Text(
                        text = profession,
                        modifier = Modifier.padding(top = Spacing.item),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                FreelancerMeta(
                    freelancer = freelancer,
                    modifier = Modifier.padding(top = Spacing.item),
                )

                freelancer.city?.let { city ->
                    Text(
                        text = city,
                        modifier = Modifier.padding(top = Spacing.item),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.fgMuted,
                    )
                }

                freelancer.bio?.let { bio ->
                    Text(
                        text = bio,
                        modifier = Modifier.padding(top = Spacing.item),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Номер мастер указал сам — по нему договариваются о том, чего
                // в форме заказа нет.
                freelancer.phone?.let { phone ->
                    MahallaButton(
                        text = stringResource(R.string.freelancer_call, phone),
                        onClick = { onEvent(FreelancerProfileEvent.CallClicked) },
                        modifier = Modifier.padding(top = Spacing.item),
                        variant = MahallaButtonVariant.Secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ServicesBlock(
    state: FreelancerProfileState,
    onEvent: (FreelancerProfileEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        when (val services = state.services) {
            is ScreenState.Loading -> CardSkeleton()

            // Пустой список — не ошибка: мастер просто не завёл услуг.
            // Кнопки «повторить» здесь нет, повторять нечего; договориться
            // можно по телефону из профиля.
            is ScreenState.Empty -> EmptyState(
                title = stringResource(R.string.freelancer_services_empty_title),
                description = stringResource(R.string.freelancer_services_empty_description),
                icon = Icons.Outlined.Handyman,
            )

            is ScreenState.Error -> InlineFailure(
                failure = services.failure,
                onRetry = { onEvent(FreelancerProfileEvent.ServicesRetry) },
            )

            is ScreenState.Content -> services.data.forEach { service ->
                MahallaChoiceCard(
                    title = service.title.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.freelancer_service_unnamed),
                    selected = service.id == state.draft.serviceId,
                    onClick = { onEvent(FreelancerProfileEvent.ServiceSelected(service.id)) },
                    description = service.description,
                    note = service.priceNote(),
                )
            }
        }
    }
}

/** Цена и длительность — то, что человек хочет знать до заказа. */
@Composable
private fun BarberService.priceNote(): String? {
    val price = priceSum.takeIf { it > 0 }?.let { sum ->
        MoneyFormatter.withCurrency(sum, stringResource(R.string.currency_uzs))
    }
    val duration = durationMinutes?.let { minutes ->
        pluralStringResource(R.plurals.freelancer_service_duration, minutes, minutes)
    }
    return listOfNotNull(price, duration).takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
}

@Composable
private fun DatesRow(
    state: FreelancerProfileState,
    onEvent: (FreelancerProfileEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        state.dates.forEachIndexed { index, date ->
            MahallaFilterChip(
                label = dateLabel(date = date, index = index),
                selected = date == state.draft.date,
                onClick = { onEvent(FreelancerProfileEvent.DateSelected(date)) },
            )
        }
    }
}

/**
 * Подпись дня: «Сегодня», «Завтра», дальше — «чт, 10.09».
 *
 * День недели берётся из ресурсов, а не из `DayOfWeek.getDisplayName`: там имя
 * зависит от локали устройства, а язык приложение выбирает своё (эпик 1.5).
 */
@Composable
private fun dateLabel(date: LocalDate, index: Int): String = when (index) {
    0 -> stringResource(R.string.booking_date_today)
    1 -> stringResource(R.string.booking_date_tomorrow)
    else -> stringResource(
        R.string.booking_date_weekday,
        stringResource(date.dayOfWeek.labelRes()),
        DateTimeFormatters.dayMonth(date),
    )
}

private fun DayOfWeek.labelRes(): Int = when (this) {
    DayOfWeek.MONDAY -> R.string.weekday_short_mon
    DayOfWeek.TUESDAY -> R.string.weekday_short_tue
    DayOfWeek.WEDNESDAY -> R.string.weekday_short_wed
    DayOfWeek.THURSDAY -> R.string.weekday_short_thu
    DayOfWeek.FRIDAY -> R.string.weekday_short_fri
    DayOfWeek.SATURDAY -> R.string.weekday_short_sat
    DayOfWeek.SUNDAY -> R.string.weekday_short_sun
}

/**
 * Время. Первым стоит «как можно скорее» — и это не украшение: в контракте
 * `scheduledAt` необязателен, а мастера чаще всего вызывают именно так.
 *
 * Подпись под сеткой обязательна: это **не** свободные слоты — занятости
 * фрилансера бэкенд не сообщает, — и выдать их за проверенные значило бы
 * обещать от имени сервера то, чего он не говорил.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimesBlock(
    state: FreelancerProfileState,
    onEvent: (FreelancerProfileEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            MahallaFilterChip(
                label = stringResource(R.string.freelancer_time_asap),
                selected = state.draft.time == null,
                onClick = { onEvent(FreelancerProfileEvent.TimeSelected(null)) },
            )
            state.times.forEach { time ->
                MahallaFilterChip(
                    label = DateTimeFormatters.time(time),
                    selected = time == state.draft.time,
                    onClick = { onEvent(FreelancerProfileEvent.TimeSelected(time)) },
                )
            }
        }
        Text(
            text = if (state.times.isEmpty()) {
                // Сегодняшний день кончился: остальные дни в календаре есть.
                stringResource(R.string.freelancer_times_empty)
            } else {
                stringResource(R.string.freelancer_times_note)
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.fgMuted,
        )
    }
}

/**
 * Адрес. Необязателен (в контракте это `address` без `@NotBlank`), поэтому
 * подпись не требует, а подсказывает. Лишнее не режется на вводе: человек не
 * поймёт, куда пропали символы, — вместо этого показывается ошибка и
 * выключается кнопка.
 */
@Composable
private fun AddressField(
    state: FreelancerProfileState,
    onEvent: (FreelancerProfileEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    MahallaTextField(
        value = draft.address,
        onValueChange = { onEvent(FreelancerProfileEvent.AddressChanged(it)) },
        label = stringResource(R.string.freelancer_address_label),
        modifier = modifier,
        placeholder = stringResource(R.string.freelancer_address_placeholder),
        errorText = pluralStringResource(
            R.plurals.freelancer_text_too_long,
            FreelancerOrderDraft.MAX_ADDRESS_LENGTH,
            FreelancerOrderDraft.MAX_ADDRESS_LENGTH,
        ).takeIf { draft.isAddressTooLong },
        enabled = !state.isOrdering,
        singleLine = false,
    )
}

@Composable
private fun CommentField(
    state: FreelancerProfileState,
    onEvent: (FreelancerProfileEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    MahallaTextField(
        value = draft.comment,
        onValueChange = { onEvent(FreelancerProfileEvent.CommentChanged(it)) },
        label = stringResource(R.string.freelancer_comment_label),
        modifier = modifier,
        placeholder = stringResource(R.string.freelancer_comment_placeholder),
        supportingText = stringResource(
            R.string.freelancer_comment_counter,
            draft.trimmedComment.length,
            FreelancerOrderDraft.MAX_COMMENT_LENGTH,
        ),
        errorText = pluralStringResource(
            R.plurals.freelancer_text_too_long,
            FreelancerOrderDraft.MAX_COMMENT_LENGTH,
            FreelancerOrderDraft.MAX_COMMENT_LENGTH,
        ).takeIf { draft.isCommentTooLong },
        enabled = !state.isOrdering,
        singleLine = false,
    )
}

/**
 * Что именно заказывают. Кнопка неактивна, пока выбор не собран, и подпись
 * рядом объясняет, чего не хватает: выключенная кнопка сама по себе не
 * сообщает ничего.
 */
@Composable
private fun SubmitBlock(
    state: FreelancerProfileState,
    onEvent: (FreelancerProfileEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        val service = state.selectedService
        if (service != null) {
            MahallaCard {
                Text(
                    text = service.title.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.freelancer_service_unnamed),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = state.whenText(),
                    style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                state.freelancer?.rateText()?.let { rate ->
                    Text(
                        text = rate,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.fgMuted,
                    )
                }
            }
        }

        // Мастер сам сказал, что заказы сейчас не берёт: молча выключенная
        // кнопка читалась бы как поломка экрана.
        if (state.isUnavailable) {
            Text(
                text = stringResource(R.string.freelancer_unavailable_note),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
        }

        MahallaButton(
            text = stringResource(R.string.freelancer_order_submit),
            onClick = { onEvent(FreelancerProfileEvent.OrderClicked) },
            state = ButtonState(enabled = state.canOrder, loading = state.isOrdering),
        )
    }
}

/** Когда придёт мастер: выбранный час либо «как можно скорее». */
@Composable
private fun FreelancerProfileState.whenText(): String {
    val date = draft.date
    val time = draft.time
    return if (date != null && time != null) {
        stringResource(
            R.string.booking_summary_when,
            DateTimeFormatters.date(date),
            DateTimeFormatters.time(time),
        )
    } else {
        stringResource(R.string.freelancer_time_asap)
    }
}

/** Подтверждение: что заказано и куда идти дальше. */
@Composable
private fun OrderedBlock(
    order: FreelancerOrder,
    onEvent: (FreelancerProfileEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
        MahallaCard {
            Text(
                text = stringResource(R.string.freelancer_order_done_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            order.serviceTitle?.let { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = order.scheduledAt
                    ?.let { DateTimeFormatters.dateTime(it) }
                    ?: stringResource(R.string.freelancer_time_asap),
                style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.freelancer_order_done_description),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
        }
        MahallaButton(
            text = stringResource(R.string.freelancer_order_open_my),
            onClick = { onEvent(FreelancerProfileEvent.MyOrdersClicked) },
        )
    }
}

/**
 * Набирать номер умеют не все устройства (и не все оболочки). Отсутствие
 * приложения-обработчика — не повод падать.
 */
private fun Context.startActivitySafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (notFound: ActivityNotFoundException) {
        // Обработчика нет — молча ничего не делаем, экран остаётся на месте.
    }
}

private const val SEPARATOR = " · "

@ThemeLanguagePreviews
@Composable
private fun FreelancerProfilePreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        FreelancerProfileContent(
            state = FreelancerProfileState(
                freelancerName = "Aziz Karimov",
                profile = ScreenState.Content(
                    Freelancer(
                        id = "f-1",
                        name = "Aziz Karimov",
                        profession = "Santexnik",
                        bio = "Quvurlar, isitish, avariya chaqiruvi.",
                        city = "Toshkent",
                        phone = "+998 90 123 45 67",
                        hourlyRateSum = 80_000,
                        experienceYears = 7,
                        ratingAvg = 4.8,
                        ratingCount = 12,
                    ),
                ),
                services = ScreenState.Content(
                    listOf(
                        BarberService(
                            id = "s-1",
                            title = "Kran almashtirish",
                            description = "Materiallar mijoznikidan",
                            priceSum = 150_000,
                            durationMinutes = 60,
                        ),
                    ),
                ),
                dates = listOf(LocalDate.of(2026, 9, 5)),
                draft = FreelancerOrderDraft(
                    serviceId = "s-1",
                    date = LocalDate.of(2026, 9, 5),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun FreelancerOrderedPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        FreelancerProfileContent(
            state = FreelancerProfileState(
                freelancerName = "Aziz Karimov",
                ordered = FreelancerOrder(
                    id = "o-1",
                    serviceTitle = "Kran almashtirish",
                    scheduledAt = Instant.parse("2026-09-06T05:30:00Z"),
                    status = FreelancerOrderStatus.Pending,
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
