package uz.mahalla.feature.booking.ui.appointment

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.CardSkeleton
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.booking.domain.AppointmentVertical
import uz.mahalla.feature.booking.ui.InlineFailure
import uz.mahalla.feature.booking.ui.appointments.AppointmentSummary
import uz.mahalla.ui.theme.Spacing
import java.time.LocalDate
import java.time.LocalTime

/**
 * Карточка записи (issue #183): открывается из «моих активностей», читает
 * актуальный статус по id — экран один на обе вертикали записи, как и «Мои
 * записи» ([uz.mahalla.feature.booking.ui.appointments.MyAppointmentsScreen]).
 *
 * Отмены и переноса здесь нет: оба действия уже есть в списке, а дублировать
 * их логику ради ещё одного места незачем — карточка только показывает
 * актуальное состояние.
 */
@Composable
fun AppointmentScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppointmentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Заведение или врач могли подтвердить, отменить или завершить запись,
    // пока приложение было в фоне.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(AppointmentEvent.ScreenResumed)
    }

    AppointmentContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun AppointmentContent(
    state: AppointmentState,
    onEvent: (AppointmentEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(state.vertical.detailTitleRes()), onBack = onBack)
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(AppointmentEvent.Refreshed) },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.gutter),
            ) {
                when (val appointment = state.appointment) {
                    is ScreenState.Loading -> CardSkeleton()

                    is ScreenState.Error -> InlineFailure(
                        failure = appointment.failure,
                        onRetry = { onEvent(AppointmentEvent.Retry) },
                    )

                    // Запись без id сервер бы не отдал — своего пустого
                    // состояния у карточки одной сущности нет.
                    is ScreenState.Empty -> Unit

                    is ScreenState.Content -> AppointmentCard(
                        appointment = appointment.data,
                        vertical = state.vertical,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppointmentCard(
    appointment: Appointment,
    vertical: AppointmentVertical,
    modifier: Modifier = Modifier,
) {
    MahallaCard(modifier = modifier) {
        AppointmentSummary(appointment = appointment, vertical = vertical)
    }
}

/** Заголовок карточки одной записи — вид активности, а не «мои записи». */
@StringRes
private fun AppointmentVertical.detailTitleRes(): Int = when (this) {
    AppointmentVertical.Barber -> R.string.activity_kind_master_appointment
    AppointmentVertical.Doctor -> R.string.activity_kind_doctor_appointment
}

@ThemeLanguagePreviews
@Composable
private fun AppointmentPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        AppointmentContent(
            state = AppointmentState(
                vertical = AppointmentVertical.Barber,
                appointment = ScreenState.Content(
                    Appointment(
                        id = "a-1",
                        placeId = "p-1",
                        serviceName = "Soch olish",
                        priceSum = 60_000,
                        date = LocalDate.of(2026, 9, 6),
                        startTime = LocalTime.of(10, 40),
                        status = AppointmentStatus.Confirmed,
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
