package uz.mahalla.feature.booking.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.CardSkeleton
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaChoiceCard
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaFilterChip
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.userMessage
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.booking.domain.BarberService
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Запись на время (issue #97): услуга → день → слот → подтверждение.
 *
 * Все шаги на одном прокручиваемом экране: выбор услуги меняет и слоты, и
 * цену, и в мастере из четырёх окон человек ходил бы назад-вперёд.
 */
@Composable
fun BookingScreen(
    onOpenMyAppointments: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                BookingEffect.OpenMyAppointments -> onOpenMyAppointments()
            }
        }
    }

    BookingContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun BookingContent(
    state: BookingState,
    onEvent: (BookingEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            // При переносе шапка называет действие, а не заведение: имени места
            // в «моих записях» нет (`AppointmentResponse` его не содержит), и
            // человеку важнее видеть, что он не записывается заново.
            title = when {
                state.isReschedule -> stringResource(R.string.booking_reschedule_title)
                state.placeName.isNotBlank() -> state.placeName
                else -> stringResource(R.string.booking_title)
            },
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            // Запись создана — выбирать больше нечего: шаги уступают место
            // подтверждению. Сам экран при этом не уходит: молчаливый переход
            // читается как «ничего не произошло» (issue #49).
            val booked = state.booked
            if (booked != null) {
                BookedBlock(appointment = booked, state = state, onEvent = onEvent)
                return@Column
            }

            // При переносе услугу не выбирают — она у переносимой записи своя.
            // Показывается она всё равно: подтверждать перенос вслепую,
            // не видя, что переносишь, человек не должен.
            if (state.isReschedule) {
                SectionHeader(title = stringResource(R.string.booking_reschedule_service_title))
                RescheduledServiceBlock(state = state)
            } else {
                SectionHeader(title = stringResource(R.string.booking_service_title))
                ServicesBlock(state = state, onEvent = onEvent)
            }

            if (state.selectedServiceId != null) {
                SectionHeader(title = stringResource(R.string.booking_date_title))
                DatesRow(state = state, onEvent = onEvent)

                SectionHeader(title = stringResource(R.string.booking_slot_title))
                SlotsBlock(state = state, onEvent = onEvent)
            }

            state.bookFailure?.let { InlineFailure(failure = it) }

            if (state.selectedServiceId != null) {
                SummaryBlock(state = state, onEvent = onEvent)
            }
        }
    }
}

@Composable
private fun ServicesBlock(
    state: BookingState,
    onEvent: (BookingEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        when (val services = state.services) {
            is ScreenState.Loading -> CardSkeleton()

            // Пустой список — не ошибка: заведение просто не завело услуги.
            // Кнопки «повторить» здесь нет, повторять нечего.
            is ScreenState.Empty -> EmptyState(
                title = stringResource(R.string.booking_services_empty_title),
                description = stringResource(R.string.booking_services_empty_description),
                icon = Icons.Outlined.EventBusy,
            )

            is ScreenState.Error -> InlineFailure(
                failure = services.failure,
                onRetry = { onEvent(BookingEvent.ServicesRetry) },
            )

            is ScreenState.Content -> services.data.forEach { service ->
                MahallaChoiceCard(
                    title = service.title.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.booking_service_unnamed),
                    selected = service.id == state.selectedServiceId,
                    onClick = { onEvent(BookingEvent.ServiceSelected(service.id)) },
                    description = service.description,
                    note = service.note(),
                )
            }
        }
    }
}

/**
 * Услуга при переносе — не выбор, а напоминание: меняется только время.
 *
 * Отказ каталога услуг здесь не показывается и повтора не предлагает: перенос
 * от него не зависит — id услуги приехал маршрутом, и слоты по нему сервер
 * отдаёт всё равно. Плашка «повторить» посреди чужого шага только сбивала бы.
 */
@Composable
private fun RescheduledServiceBlock(
    state: BookingState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        val service = state.selectedService
        when {
            service != null -> MahallaCard {
                Text(
                    text = service.title.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.booking_service_unnamed),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                service.note()?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalMahallaColors.current.fgMuted,
                    )
                }
            }

            state.services is ScreenState.Loading -> CardSkeleton()
        }

        Text(
            text = stringResource(R.string.booking_reschedule_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMahallaColors.current.fgMuted,
        )
    }
}

/** Цена и длительность одной строкой: вместе они и отвечают «сколько это». */
@Composable
private fun BarberService.note(): String? {
    val price = priceSum.takeIf { it > 0 }?.let {
        MoneyFormatter.withCurrency(it, stringResource(R.string.currency_uzs))
    }
    val duration = durationMinutes?.let {
        pluralStringResource(R.plurals.booking_duration_minutes, it, it)
    }
    return listOfNotNull(price, duration).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun DatesRow(
    state: BookingState,
    onEvent: (BookingEvent) -> Unit,
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
                selected = date == state.selectedDate,
                onClick = { onEvent(BookingEvent.DateSelected(date)) },
            )
        }
    }
}

/**
 * Подпись дня: «Сегодня», «Завтра», дальше — «чт, 10.09».
 *
 * День недели берётся из ресурсов, а не из `DayOfWeek.getDisplayName`: там имя
 * зависит от локали устройства, а язык приложение выбирает своё (эпик 1.5), и
 * узбекский интерфейс на русском телефоне показывал бы русские дни.
 *
 * Порядковый номер, а не сравнение с «сегодня»: список дней собирает домен от
 * одного и того же момента, и первый в нём — сегодняшний по построению.
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotsBlock(
    state: BookingState,
    onEvent: (BookingEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val slots = state.slots) {
        is ScreenState.Loading -> CardSkeleton(modifier = modifier)

        // Свободных слотов нет — это ответ сервера, а не сбой: человеку нужно
        // выбрать другой день, и текст говорит именно это.
        is ScreenState.Empty -> Text(
            text = stringResource(R.string.booking_slots_empty),
            modifier = modifier,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMahallaColors.current.fgMuted,
        )

        is ScreenState.Error -> InlineFailure(
            failure = slots.failure,
            onRetry = { onEvent(BookingEvent.SlotsRetry) },
            modifier = modifier,
        )

        is ScreenState.Content -> FlowRow(
            modifier = modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            slots.data.forEach { slot ->
                MahallaFilterChip(
                    label = DateTimeFormatters.time(slot),
                    selected = slot == state.selectedTime,
                    onClick = { onEvent(BookingEvent.TimeSelected(slot)) },
                )
            }
        }
    }
}

/**
 * Что именно подтверждают. Кнопка неактивна, пока выбор не собран целиком, и
 * подпись под ней называет недостающий шаг — иначе выключенная кнопка не
 * объясняет, чего от человека ждут.
 */
@Composable
private fun SummaryBlock(
    state: BookingState,
    onEvent: (BookingEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        val service = state.selectedService
        val date = state.selectedDate
        val time = state.selectedTime
        // Итог рисуется по дню и слоту, а не по найденной услуге: при переносе
        // её может не оказаться в каталоге, а выбор от этого не перестаёт быть
        // собранным (см. `BookingState.canBook`).
        if (date != null && time != null) {
            MahallaCard {
                service?.let {
                    Text(
                        text = it.title.takeIf { title -> title.isNotBlank() }
                            ?: stringResource(R.string.booking_service_unnamed),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.booking_summary_when,
                        DateTimeFormatters.date(date),
                        DateTimeFormatters.time(time),
                    ),
                    style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                service?.note()?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.fgMuted,
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.booking_pick_slot_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
        }

        MahallaButton(
            text = stringResource(
                if (state.isReschedule) {
                    R.string.booking_reschedule_submit
                } else {
                    R.string.booking_submit
                },
            ),
            onClick = { onEvent(BookingEvent.BookClicked) },
            state = ButtonState(enabled = state.canBook, loading = state.isBooking),
        )
    }
}

/** Подтверждение: что записано и куда идти дальше. */
@Composable
private fun BookedBlock(
    appointment: Appointment,
    state: BookingState,
    onEvent: (BookingEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
        MahallaCard {
            Text(
                text = stringResource(
                    if (state.isReschedule) {
                        R.string.booking_reschedule_done_title
                    } else {
                        R.string.booking_done_title
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            appointment.serviceName?.let { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            val date = appointment.date
            val time = appointment.startTime
            if (date != null && time != null) {
                Text(
                    text = stringResource(
                        R.string.booking_summary_when,
                        DateTimeFormatters.date(date),
                        DateTimeFormatters.time(time),
                    ),
                    style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(
                    when {
                        !state.isReschedule -> R.string.booking_done_description
                        // «Прежняя запись отменена» рядом с красным «отменить
                        // не удалось» — это два противоречащих утверждения в
                        // одной карточке; про старую запись тогда говорит
                        // только предупреждение ниже.
                        state.previousCancelled ->
                            R.string.booking_reschedule_done_description

                        else -> R.string.booking_reschedule_done_description_kept
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
        }

        // Новое время получено, а старую запись снять не удалось: у человека
        // осталось две, и молчать об этом нельзя — заведение ждёт его дважды.
        if (state.isReschedule && !state.previousCancelled) {
            Text(
                text = stringResource(R.string.booking_reschedule_old_kept),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        MahallaButton(
            text = stringResource(R.string.my_appointments_title),
            onClick = { onEvent(BookingEvent.MyAppointmentsClicked) },
        )
    }
}

/**
 * Отказ внутри экрана: текст сервера, подробности и — если есть чем — повтор.
 * `ApiErrorState` здесь не годится: он занимает экран целиком, а услуги,
 * слоты и подтверждение отказывают по отдельности.
 */
@Composable
internal fun InlineFailure(
    failure: ApiFailure,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        Text(
            text = failure.userMessage(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        failure.server?.let { MahallaErrorDetails(server = it) }
        if (onRetry != null) {
            MahallaButton(
                text = stringResource(R.string.action_retry),
                onClick = onRetry,
                variant = MahallaButtonVariant.Secondary,
                fillWidth = false,
            )
        }
    }
}

@ThemeLanguagePreviews
@Composable
private fun BookingScreenPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        BookingContent(
            state = BookingState(
                placeName = "Barber House",
                services = ScreenState.Content(
                    listOf(
                        BarberService(
                            id = "s-1",
                            title = "Soch olish",
                            description = "Mashinka va qaychi bilan",
                            priceSum = 60_000,
                            durationMinutes = 40,
                        ),
                        BarberService(id = "s-2", title = "Soqol", priceSum = 30_000),
                    ),
                ),
                selectedServiceId = "s-1",
                dates = listOf(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 5)),
                selectedDate = LocalDate.of(2026, 9, 4),
                slots = ScreenState.Content(
                    listOf(LocalTime.of(10, 0), LocalTime.of(10, 40), LocalTime.of(11, 20)),
                ),
                selectedTime = LocalTime.of(10, 40),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun BookingReschedulePreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        BookingContent(
            state = BookingState(
                isReschedule = true,
                services = ScreenState.Content(
                    listOf(BarberService(id = "s-1", title = "Soch olish", priceSum = 60_000)),
                ),
                selectedServiceId = "s-1",
                dates = listOf(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 5)),
                selectedDate = LocalDate.of(2026, 9, 5),
                slots = ScreenState.Content(listOf(LocalTime.of(10, 0), LocalTime.of(11, 20))),
                selectedTime = LocalTime.of(11, 20),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

/** Самый неприятный исход переноса: новая запись есть, старая не снялась. */
@ThemeLanguagePreviews
@Composable
private fun BookingRescheduleKeptOldPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        BookingContent(
            state = BookingState(
                isReschedule = true,
                previousCancelled = false,
                booked = Appointment(
                    id = "a-2",
                    serviceName = "Soch olish",
                    date = LocalDate.of(2026, 9, 5),
                    startTime = LocalTime.of(11, 20),
                    status = AppointmentStatus.Pending,
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun BookingDonePreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        BookingContent(
            state = BookingState(
                placeName = "Barber House",
                booked = Appointment(
                    id = "a-1",
                    serviceName = "Soch olish",
                    date = LocalDate.of(2026, 9, 5),
                    startTime = LocalTime.of(10, 40),
                    status = AppointmentStatus.Pending,
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
