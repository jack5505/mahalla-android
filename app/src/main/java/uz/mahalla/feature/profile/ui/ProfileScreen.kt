package uz.mahalla.feature.profile.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import uz.mahalla.R
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.locale.AppLanguage
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaDialog
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaListItem
import uz.mahalla.core.ui.components.MahallaSwitchRow
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.userMessage
import uz.mahalla.data.prefs.AppSettings
import uz.mahalla.data.prefs.ThemeMode
import uz.mahalla.data.prefs.UserProfile
import uz.mahalla.feature.profile.domain.DeviceSession
import uz.mahalla.feature.profile.domain.DeviceSessionStatus
import uz.mahalla.feature.role.domain.UserRole
import uz.mahalla.feature.role.ui.labelRes
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import java.time.Instant

/**
 * Профиль (issue #61): кто вошёл, настройки приложения, устройства с открытым
 * входом и выход из аккаунта.
 *
 * @param onChangeServer открыть экран адреса бэкенда (issue #26); `null` —
 * сборке менять адрес не разрешено, строки нет. После входа welcome с той же
 * кнопкой недостижим, а сервер сменить бывает нужно (переехал стенд).
 * @param onLoggedOut вышли из аккаунта: приложение возвращается в онбординг.
 * @param onOpenRole открыть «кто вы» и анкеты (issue #84): роль меняется —
 * вчерашний покупатель открывает кафе, — а в онбординге анкету можно было
 * пропустить.
 * @param onOpenMyPlaces открыть «мои заведения» (issue #94). Строка видна
 * только продавцу: до неё судьбу отправленной заявки в приложении было не
 * видно вовсе.
 */
@Composable
fun ProfileScreen(
    onLoggedOut: () -> Unit,
    onOpenRole: () -> Unit,
    onOpenMyPlaces: () -> Unit,
    modifier: Modifier = Modifier,
    onChangeServer: (() -> Unit)? = null,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ProfileEffect.RecreateActivity -> (context as? Activity)?.recreate()
                is ProfileEffect.OpenHttpInspector -> context.startActivity(effect.intent)
                ProfileEffect.LoggedOut -> onLoggedOut()
            }
        }
    }

    // Вход с другого устройства мог случиться, пока приложение было в фоне:
    // список устройств, показанный час назад, ничего не стоит.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(ProfileEvent.ScreenResumed)
    }

    ProfileContentScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onOpenRole = onOpenRole,
        onOpenMyPlaces = onOpenMyPlaces,
        modifier = modifier,
        onChangeServer = onChangeServer,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileContentScreen(
    state: ProfileState,
    onEvent: (ProfileEvent) -> Unit,
    onOpenRole: () -> Unit,
    onOpenMyPlaces: () -> Unit,
    modifier: Modifier = Modifier,
    onChangeServer: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.profile_title))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            ProfileHeader(profile = state.profile)

            // Анкеты покупателя и продавца (issue #84). Подпись — текущая
            // роль: строка «Моя анкета» без неё не отвечает на вопрос, кем
            // человек в приложении числится сейчас.
            val role = UserRole.fromStoredValue(state.settings.roleId)
            MahallaListItem(
                title = stringResource(R.string.role_profile_entry),
                subtitle = stringResource(role.labelRes()),
                onClick = onOpenRole,
            )

            // «Мои заведения» (issue #94) — только продавцу: покупателю
            // показывать список, который всегда пуст, незачем. Роль он меняет
            // строкой выше, и тогда строка появится.
            if (role == UserRole.Provider) {
                MahallaListItem(
                    title = stringResource(R.string.my_places_title),
                    subtitle = stringResource(R.string.my_places_profile_subtitle),
                    onClick = onOpenMyPlaces,
                )
            }

            Text(
                text = stringResource(R.string.profile_language),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.gap)) {
                AppLanguage.entries.forEach { language ->
                    FilterChip(
                        selected = state.settings.language == language,
                        onClick = { onEvent(ProfileEvent.LanguageSelected(language)) },
                        label = { Text(stringResource(language.labelRes())) },
                    )
                }
            }

            Text(
                text = stringResource(R.string.profile_theme),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.gap)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.settings.themeMode == mode,
                        onClick = { onEvent(ProfileEvent.ThemeSelected(mode)) },
                        label = { Text(stringResource(mode.labelRes())) },
                    )
                }
            }

            if (onChangeServer != null) {
                MahallaListItem(
                    title = stringResource(R.string.backend_url_change),
                    // Показываем адрес, на который приложение ходит сейчас: без
                    // него строка не отвечает на главный вопрос «а куда сейчас?».
                    subtitle = state.settings.backendBaseUrl
                        ?: stringResource(R.string.backend_url_default_value),
                    onClick = onChangeServer,
                )
            }

            // Инспектор трафика (issue #30): в release строки нет — библиотека
            // приезжает вариантом no-op и отвечает `isAvailable = false`.
            if (state.httpInspectorAvailable) {
                MahallaListItem(
                    title = stringResource(R.string.http_inspector_open),
                    subtitle = stringResource(R.string.http_inspector_subtitle),
                    onClick = { onEvent(ProfileEvent.HttpInspectorRequested) },
                )
            }

            SectionHeader(title = stringResource(R.string.profile_devices_title))
            DeviceSessionsSection(state = state, onEvent = onEvent)

            MahallaButton(
                text = stringResource(R.string.profile_logout),
                onClick = { onEvent(ProfileEvent.LogoutRequested) },
                variant = MahallaButtonVariant.Destructive,
                state = ButtonState(loading = state.loggingOut),
            )
        }
    }

    if (state.confirmLogout) {
        MahallaDialog(
            title = stringResource(R.string.profile_logout_title),
            text = stringResource(R.string.profile_logout_message),
            confirmLabel = stringResource(R.string.profile_logout),
            onConfirm = { onEvent(ProfileEvent.LogoutConfirmed) },
            onDismiss = { onEvent(ProfileEvent.LogoutDismissed) },
            destructive = true,
        )
    }

    state.confirmRevoke?.let { session ->
        MahallaDialog(
            title = stringResource(R.string.profile_device_revoke_title),
            text = stringResource(
                R.string.profile_device_revoke_message,
                session.displayName(),
            ),
            confirmLabel = stringResource(R.string.profile_device_revoke),
            onConfirm = { onEvent(ProfileEvent.SessionRevokeConfirmed) },
            onDismiss = { onEvent(ProfileEvent.SessionRevokeDismissed) },
            destructive = true,
        )
    }
}

/**
 * Шапка: аватар, имя и номер. Картинки в приложении пока нет (загрузчик
 * изображений — отдельная задача), поэтому аватар — круг с инициалами;
 * `avatarUrl` уже хранится и подставится в него без изменений экрана.
 */
@Composable
private fun ProfileHeader(profile: UserProfile, modifier: Modifier = Modifier) {
    MahallaCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val initials = profile.initials()
            Box(
                modifier = Modifier
                    .size(AVATAR_SIZE)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (initials.isEmpty()) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        // Иконка дублирует имя рядом — для TalkBack пустая.
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                } else {
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = profile.fullName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.profile_name_unknown),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = profile.phone?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.profile_phone_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalMahallaColors.current.fgMuted,
                )
            }
        }
    }
}

/**
 * Устройства с открытым входом.
 *
 * Состояния разложены руками, а не `ScreenStateHost`: экран прокручивается
 * целиком, а `ApiErrorState` внутри несёт собственную прокрутку — вложенная в
 * родительскую, она измерялась бы бесконечной высотой.
 */
@Composable
private fun DeviceSessionsSection(
    state: ProfileState,
    onEvent: (ProfileEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
    ) {
        when (val sessions = state.sessions) {
            is ScreenState.Loading -> ListSkeleton(itemCount = DEVICE_SKELETONS)

            // Пусто здесь означать может только одно: сервер не считает
            // активной ни одну сессию, включая нашу.
            is ScreenState.Empty -> Text(
                text = stringResource(R.string.profile_devices_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )

            is ScreenState.Error -> {
                Text(
                    text = sessions.failure.userMessage(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                sessions.failure.server?.let { server -> MahallaErrorDetails(server = server) }
                MahallaButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { onEvent(ProfileEvent.SessionsRetryRequested) },
                    variant = MahallaButtonVariant.Secondary,
                    fillWidth = false,
                )
            }

            is ScreenState.Content -> sessions.data.forEach { session ->
                DeviceSessionCard(
                    session = session,
                    pending = state.pendingSessionId == session.id,
                    // Пока идёт запрос по одной строке, остальные не трогаем:
                    // ответы приехали бы на список, которого уже нет.
                    enabled = state.pendingSessionId == null,
                    onEvent = onEvent,
                )
            }
        }

        // Отказ отзыва или доверия: текст сервера (issue #34), а не молчание.
        state.sessionFailure?.let { failure ->
            Text(
                text = failure.userMessage(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DeviceSessionCard(
    session: DeviceSession,
    pending: Boolean,
    enabled: Boolean,
    onEvent: (ProfileEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    MahallaCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = session.displayName(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (session.isCurrent) {
                MahallaBadge(
                    text = stringResource(R.string.profile_device_current),
                    tone = MahallaTone.Success,
                )
            } else {
                session.status.labelRes()?.let { labelRes ->
                    MahallaBadge(text = stringResource(labelRes), tone = MahallaTone.Warning)
                }
            }
        }

        session.lastActivityAt?.let { moment ->
            Text(
                text = stringResource(
                    R.string.profile_device_last_activity,
                    DateTimeFormatters.dateTime(moment),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
        }
        session.lastIp?.let { ip ->
            Text(
                text = stringResource(R.string.profile_device_ip, ip),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
        }

        MahallaSwitchRow(
            title = stringResource(R.string.profile_device_trusted),
            checked = session.trusted,
            onCheckedChange = { trusted ->
                onEvent(ProfileEvent.SessionTrustToggled(session, trusted))
            },
            description = stringResource(R.string.profile_device_trusted_description),
            enabled = enabled,
        )

        // Своё устройство отзывать нельзя: это был бы выход, о котором экран
        // не сказал ни слова. Для него есть кнопка «Выйти».
        if (!session.isCurrent) {
            MahallaButton(
                text = stringResource(R.string.profile_device_revoke),
                onClick = { onEvent(ProfileEvent.SessionRevokeRequested(session)) },
                variant = MahallaButtonVariant.Ghost,
                state = ButtonState(enabled = enabled, loading = pending),
                fillWidth = false,
            )
        }
    }
}

@Composable
private fun DeviceSession.displayName(): String = deviceName
    ?: platform
    ?: stringResource(R.string.profile_device_unknown)

/** Статус показываем только тогда, когда он объясняет что-то человеку. */
private fun DeviceSessionStatus.labelRes(): Int? = when (this) {
    DeviceSessionStatus.Locked -> R.string.profile_device_status_locked
    DeviceSessionStatus.PinRequired -> R.string.profile_device_status_pin_required
    DeviceSessionStatus.PendingPinSetup -> R.string.profile_device_status_pending_pin
    DeviceSessionStatus.Active, DeviceSessionStatus.Revoked, DeviceSessionStatus.Unknown -> null
}

private fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.SYSTEM -> R.string.language_system
    AppLanguage.UZBEK -> R.string.language_uz
    AppLanguage.RUSSIAN -> R.string.language_ru
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

private val AVATAR_SIZE = 56.dp
private const val DEVICE_SKELETONS = 2

@ThemeLanguagePreviews
@Composable
private fun ProfilePreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        ProfileContentScreen(
            state = ProfileState(
                // Продавец: иначе строки «Мои заведения» в превью не видно.
                settings = AppSettings(roleId = UserRole.Provider.storedValue),
                profile = UserProfile(
                    phone = "+998 90 123 45 67",
                    fullName = "Jahongir Sabirov",
                ),
                sessions = ScreenState.Content(
                    listOf(
                        DeviceSession(
                            id = "current",
                            deviceName = "Samsung SM-A536B",
                            lastActivityAt = Instant.parse("2026-08-30T09:12:00Z"),
                            lastIp = "84.54.0.1",
                            isCurrent = true,
                            trusted = true,
                        ),
                        DeviceSession(
                            id = "other",
                            deviceName = "Xiaomi Redmi Note 12",
                            status = DeviceSessionStatus.PinRequired,
                            lastActivityAt = Instant.parse("2026-08-28T20:40:00Z"),
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onOpenRole = {},
            onOpenMyPlaces = {},
        )
    }
}
