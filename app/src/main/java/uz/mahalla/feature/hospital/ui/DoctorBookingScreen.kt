package uz.mahalla.feature.hospital.ui

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
import androidx.compose.material.icons.outlined.MedicalServices
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
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.CardSkeleton
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaChoiceCard
import uz.mahalla.core.ui.components.MahallaFilterChip
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.booking.ui.InlineFailure
import uz.mahalla.feature.hospital.domain.Doctor
import uz.mahalla.feature.hospital.domain.DoctorAppointmentDraft
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Запись к врачу (issue #99): врач → день → время → жалоба → подтверждение.
 *
 * Все шаги на одном прокручиваемом экране — как в брони (issue #97): выбор
 * врача меняет цену приёма, и в мастере из четырёх окон человек ходил бы
 * назад-вперёд.
 */
@Composable
fun DoctorBookingScreen(
    onOpenMyAppointments: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DoctorBookingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                DoctorBookingEffect.OpenMyAppointments -> onOpenMyAppointments()
            }
        }
    }

    DoctorBookingContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun DoctorBookingContent(
    state: DoctorBookingState,
    onEvent: (DoctorBookingEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = state.placeName.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.doctor_booking_title),
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // Жалобу набирают с клавиатуры: без этого её поле оказалось бы
                // под ней вместе с кнопкой подтверждения.
                .imePadding()
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            // Запись создана — выбирать больше нечего: шаги уступают место
            // подтверждению. Сам экран при этом не уходит: молчаливый переход
            // читается как «ничего не произошло» (issue #49).
            val booked = state.booked
            if (booked != null) {
                BookedBlock(appointment = booked, onEvent = onEvent)
                return@Column
            }

            SectionHeader(title = stringResource(R.string.doctor_booking_doctor_title))
            DoctorsBlock(state = state, onEvent = onEvent)

            if (state.draft.doctorId != null) {
                SectionHeader(title = stringResource(R.string.booking_date_title))
                DatesRow(state = state, onEvent = onEvent)

                SectionHeader(title = stringResource(R.string.doctor_booking_time_title))
                TimesBlock(state = state, onEvent = onEvent)

                SectionHeader(title = stringResource(R.string.doctor_booking_complaint_title))
                ComplaintField(state = state, onEvent = onEvent)
            }

            state.bookFailure?.let { InlineFailure(failure = it) }

            if (state.draft.doctorId != null) {
                SummaryBlock(state = state, onEvent = onEvent)
            }
        }
    }
}

@Composable
private fun DoctorsBlock(
    state: DoctorBookingState,
    onEvent: (DoctorBookingEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        when (val doctors = state.doctors) {
            is ScreenState.Loading -> CardSkeleton()

            // Пустой список — не ошибка: больница просто не завела врачей.
            // Кнопки «повторить» здесь нет, повторять нечего.
            is ScreenState.Empty -> EmptyState(
                title = stringResource(R.string.doctor_booking_doctors_empty_title),
                description = stringResource(
                    R.string.doctor_booking_doctors_empty_description,
                ),
                icon = Icons.Outlined.MedicalServices,
            )

            is ScreenState.Error -> InlineFailure(
                failure = doctors.failure,
                onRetry = { onEvent(DoctorBookingEvent.DoctorsRetry) },
            )

            is ScreenState.Content -> doctors.data.forEach { doctor ->
                MahallaChoiceCard(
                    title = doctor.name.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.doctor_booking_doctor_unnamed),
                    selected = doctor.id == state.draft.doctorId,
                    onClick = { onEvent(DoctorBookingEvent.DoctorSelected(doctor.id)) },
                    // Специальность важнее биографии: её и ищут глазами.
                    description = doctor.specialty ?: doctor.bio,
                    note = doctor.priceNote(),
                )
            }
        }
    }
}

/** Цена приёма — то, что человек хочет знать до записи. */
@Composable
private fun Doctor.priceNote(): String? = consultationPriceSum.takeIf { it > 0 }?.let { price ->
    stringResource(
        R.string.doctor_booking_price,
        MoneyFormatter.withCurrency(price, stringResource(R.string.currency_uzs)),
    )
}

@Composable
private fun DatesRow(
    state: DoctorBookingState,
    onEvent: (DoctorBookingEvent) -> Unit,
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
                onClick = { onEvent(DoctorBookingEvent.DateSelected(date)) },
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
 * Время приёма. Подпись под сеткой обязательна: это **не** свободные слоты —
 * занятость врача бэкенд не сообщает, — и выдать их за проверенные значило бы
 * обещать от имени сервера то, чего он не говорил.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimesBlock(
    state: DoctorBookingState,
    onEvent: (DoctorBookingEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        if (state.times.isEmpty()) {
            // Сегодняшний приём закончился: остальные дни в календаре есть.
            Text(
                text = stringResource(R.string.doctor_booking_times_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
            return@Column
        }

        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            state.times.forEach { time ->
                MahallaFilterChip(
                    label = DateTimeFormatters.time(time),
                    selected = time == state.draft.time,
                    onClick = { onEvent(DoctorBookingEvent.TimeSelected(time)) },
                )
            }
        }
        Text(
            text = stringResource(R.string.doctor_booking_times_note),
            style = MaterialTheme.typography.bodySmall,
            color = colors.fgMuted,
        )
    }
}

/**
 * Жалоба. Необязательна (в контракте это `complaint` без `@NotBlank`), поэтому
 * подпись говорит «если хотите», а не требует. Лишнее не режется на вводе:
 * человек не поймёт, куда пропали символы, — вместо этого показывается ошибка
 * и выключается кнопка.
 */
@Composable
private fun ComplaintField(
    state: DoctorBookingState,
    onEvent: (DoctorBookingEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    MahallaTextField(
        value = draft.complaint,
        onValueChange = { onEvent(DoctorBookingEvent.ComplaintChanged(it)) },
        label = stringResource(R.string.doctor_booking_complaint_label),
        modifier = modifier,
        placeholder = stringResource(R.string.doctor_booking_complaint_placeholder),
        supportingText = stringResource(
            R.string.doctor_booking_complaint_counter,
            draft.trimmedComplaint.length,
            DoctorAppointmentDraft.MAX_COMPLAINT_LENGTH,
        ),
        errorText = pluralStringResource(
            R.plurals.doctor_booking_complaint_too_long,
            DoctorAppointmentDraft.MAX_COMPLAINT_LENGTH,
            DoctorAppointmentDraft.MAX_COMPLAINT_LENGTH,
        ).takeIf { draft.isComplaintTooLong },
        enabled = !state.isBooking,
        singleLine = false,
    )
}

/**
 * Что именно подтверждают. Кнопка неактивна, пока выбор не собран целиком, и
 * подпись над ней называет недостающий шаг — иначе выключенная кнопка не
 * объясняет, чего от человека ждут.
 */
@Composable
private fun SummaryBlock(
    state: DoctorBookingState,
    onEvent: (DoctorBookingEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        val doctor = state.selectedDoctor
        val date = state.draft.date
        val time = state.draft.time
        if (doctor != null && date != null && time != null) {
            MahallaCard {
                Text(
                    text = doctor.name.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.doctor_booking_doctor_unnamed),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                doctor.specialty?.let { specialty ->
                    Text(
                        text = specialty,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.fgMuted,
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
                doctor.priceNote()?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.fgMuted,
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.doctor_booking_pick_time_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
        }

        MahallaButton(
            text = stringResource(R.string.doctor_booking_submit),
            onClick = { onEvent(DoctorBookingEvent.BookClicked) },
            state = ButtonState(enabled = state.canBook, loading = state.isBooking),
        )
    }
}

/** Подтверждение: что записано и куда идти дальше. */
@Composable
private fun BookedBlock(
    appointment: Appointment,
    onEvent: (DoctorBookingEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
        MahallaCard {
            Text(
                text = stringResource(R.string.doctor_booking_done_title),
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
                text = stringResource(R.string.doctor_booking_done_description),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
        }

        MahallaButton(
            text = stringResource(R.string.my_doctor_appointments_title),
            onClick = { onEvent(DoctorBookingEvent.MyAppointmentsClicked) },
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun DoctorBookingPreview() {
    PreviewSurface(modifier = Modifier.fillMaxWidth()) {
        DoctorBookingContent(
            state = DoctorBookingState(
                placeName = "Shifo klinikasi",
                doctors = ScreenState.Content(
                    listOf(
                        Doctor(
                            id = "d-1",
                            name = "Aliyev Bekzod",
                            specialty = "Terapevt",
                            consultationPriceSum = 90_000,
                        ),
                        Doctor(id = "d-2", name = "Karimova Dilnoza", specialty = "Kardiolog"),
                    ),
                ),
                dates = listOf(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 5)),
                times = listOf(LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0)),
                draft = DoctorAppointmentDraft(
                    doctorId = "d-1",
                    date = LocalDate.of(2026, 9, 4),
                    time = LocalTime.of(9, 30),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun DoctorBookingDonePreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        DoctorBookingContent(
            state = DoctorBookingState(
                placeName = "Shifo klinikasi",
                booked = Appointment(
                    id = "a-1",
                    serviceName = "Aliyev Bekzod",
                    date = LocalDate.of(2026, 9, 5),
                    startTime = LocalTime.of(9, 30),
                    status = AppointmentStatus.Pending,
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
