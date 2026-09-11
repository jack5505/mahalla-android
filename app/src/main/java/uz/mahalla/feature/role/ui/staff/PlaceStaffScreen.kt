package uz.mahalla.feature.role.ui.staff

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.FilterChipUi
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaBottomSheet
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaDialog
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaFilterRow
import uz.mahalla.core.ui.components.MahallaIconButton
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.userMessage
import uz.mahalla.feature.role.domain.PlaceStaffMember
import uz.mahalla.feature.role.domain.PlaceStaffRole
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * «Сотрудники» (issue #189): список, добавление по ID, смена роли и удаление
 * с подтверждением. Экран открывается только со «своих заведений» — владелец
 * там уже отфильтрован клиентом, а бэкенд проверяет доступ ещё раз сам.
 */
@Composable
fun PlaceStaffScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaceStaffViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PlaceStaffContentScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun PlaceStaffContentScreen(
    state: PlaceStaffState,
    onEvent: (PlaceStaffEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.place_staff_title), onBack = onBack)
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(PlaceStaffEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                // Отказ действия — над списком, а не вместо него: строки уже
                // на экране, и прятать их из-за неудавшейся кнопки незачем.
                state.actionFailure?.let { failure ->
                    item(key = "action-failure") { InlineFailure(failure = failure) }
                }
                placeStaffItems(state = state, onEvent = onEvent)
            }
        }
    }

    if (state.addForm.visible) {
        AddStaffSheet(form = state.addForm, onEvent = onEvent)
    }

    state.confirmRemove?.let { member ->
        MahallaDialog(
            title = stringResource(R.string.place_staff_remove_title),
            text = stringResource(R.string.place_staff_remove_message, member.userId),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { onEvent(PlaceStaffEvent.RemoveConfirmed) },
            onDismiss = { onEvent(PlaceStaffEvent.RemoveDismissed) },
            destructive = true,
        )
    }
}

/**
 * Состояния разложены руками, а не через `ScreenStateHost`: тот рисует
 * `ApiErrorState` с собственной прокруткой, а внутри `LazyColumn` вложенная
 * прокрутка меряется бесконечной высотой и роняет измерение — та же причина,
 * что и у «моих заведений».
 */
private fun LazyListScope.placeStaffItems(
    state: PlaceStaffState,
    onEvent: (PlaceStaffEvent) -> Unit,
) {
    when (val staff = state.staff) {
        is ScreenState.Loading -> item(key = "loading") {
            ListSkeleton(itemCount = LIST_SKELETONS)
        }

        is ScreenState.Empty -> item(key = "empty") {
            EmptyState(
                title = stringResource(R.string.place_staff_empty_title),
                description = stringResource(R.string.place_staff_empty_description),
                icon = Icons.Outlined.Person,
                actionLabel = stringResource(R.string.place_staff_add),
                onAction = { onEvent(PlaceStaffEvent.AddSheetOpened) },
            )
        }

        is ScreenState.Error -> item(key = "error") {
            InlineFailure(
                failure = staff.failure,
                onRetry = { onEvent(PlaceStaffEvent.Retry) },
            )
        }

        is ScreenState.Content -> {
            items(staff.data, key = PlaceStaffMember::userId) { member ->
                PlaceStaffCard(
                    member = member,
                    pending = state.pendingUserId == member.userId,
                    enabled = state.pendingUserId == null,
                    onEvent = onEvent,
                )
            }
            item(key = "add") {
                MahallaButton(
                    text = stringResource(R.string.place_staff_add),
                    onClick = { onEvent(PlaceStaffEvent.AddSheetOpened) },
                    variant = MahallaButtonVariant.Secondary,
                )
            }
        }
    }
}

/** Карточка сотрудника: ID, роль (её же меняют чипы) и удаление. */
@Composable
private fun PlaceStaffCard(
    member: PlaceStaffMember,
    pending: Boolean,
    enabled: Boolean,
    onEvent: (PlaceStaffEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    MahallaCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.place_staff_user_id_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.fgMuted,
                )
                Text(
                    text = member.userId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            MahallaBadge(text = stringResource(member.role.labelRes()), tone = MahallaTone.Neutral)
            MahallaIconButton(
                icon = Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.action_delete),
                onClick = { onEvent(PlaceStaffEvent.RemoveRequested(member)) },
                enabled = enabled && !pending,
            )
        }

        MahallaFilterRow(
            items = SELECTABLE_ROLES.map { role ->
                FilterChipUi(id = role.name, label = stringResource(role.labelRes()))
            },
            selectedId = member.role.name,
            onSelect = { id ->
                onEvent(PlaceStaffEvent.RoleChangeRequested(member, PlaceStaffRole.valueOf(id)))
            },
            modifier = Modifier.padding(top = Spacing.item),
            // Пока идёт запрос по этой строке, чипы не отвечают — иначе тап
            // выглядел бы принятым, а событие тихо гасил бы гард во ViewModel.
            enabled = enabled && !pending,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddStaffSheet(
    form: AddStaffFormState,
    onEvent: (PlaceStaffEvent) -> Unit,
) {
    MahallaBottomSheet(
        onDismiss = { onEvent(PlaceStaffEvent.AddSheetDismissed) },
        title = stringResource(R.string.place_staff_add),
    ) {
        MahallaTextField(
            value = form.userId,
            onValueChange = { onEvent(PlaceStaffEvent.AddUserIdChanged(it)) },
            label = stringResource(R.string.place_staff_user_id_label),
            supportingText = stringResource(R.string.place_staff_user_id_supporting),
            errorText = if (form.userIdError) {
                stringResource(R.string.place_staff_user_id_error)
            } else {
                null
            },
            enabled = !form.submitting,
        )
        Text(
            text = stringResource(R.string.place_staff_role_label),
            style = MaterialTheme.typography.labelLarge,
            color = LocalMahallaColors.current.fgMuted,
        )
        MahallaFilterRow(
            items = SELECTABLE_ROLES.map { role ->
                FilterChipUi(id = role.name, label = stringResource(role.labelRes()))
            },
            selectedId = form.role.name,
            onSelect = { id -> onEvent(PlaceStaffEvent.AddRoleSelected(PlaceStaffRole.valueOf(id))) },
        )
        form.failure?.let { failure -> InlineFailure(failure = failure) }
        MahallaButton(
            text = stringResource(R.string.place_staff_add_submit),
            onClick = { onEvent(PlaceStaffEvent.AddSubmitted) },
            state = ButtonState(loading = form.submitting),
        )
    }
}

/**
 * Отказ внутри списка: текст сервера, подробности и — если есть чем — повтор.
 * `ApiErrorState` здесь не годится: он прокручивается сам, как у «моих
 * заведений».
 */
@Composable
private fun InlineFailure(
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

/** Подписи ролей — домен про Android не знает, сопоставление живёт здесь. */
@StringRes
private fun PlaceStaffRole.labelRes(): Int = when (this) {
    PlaceStaffRole.Owner -> R.string.place_staff_role_owner
    PlaceStaffRole.Manager -> R.string.place_staff_role_manager
    PlaceStaffRole.Staff -> R.string.place_staff_role_staff
    PlaceStaffRole.Unknown -> R.string.place_staff_role_unknown
}

/** Незнакомую бэкенду роль назначить нельзя — только увидеть в бейдже. */
private val SELECTABLE_ROLES = listOf(PlaceStaffRole.Staff, PlaceStaffRole.Manager, PlaceStaffRole.Owner)

private const val LIST_SKELETONS = 3

@ThemeLanguagePreviews
@Composable
private fun PlaceStaffScreenPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        PlaceStaffContentScreen(
            state = PlaceStaffState(
                placeId = "p-1",
                staff = ScreenState.Content(
                    listOf(
                        PlaceStaffMember(
                            userId = "8f14e45f-ceea-4a3d-8f1e-000000000001",
                            role = PlaceStaffRole.Owner,
                        ),
                        PlaceStaffMember(
                            userId = "8f14e45f-ceea-4a3d-8f1e-000000000002",
                            role = PlaceStaffRole.Staff,
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
