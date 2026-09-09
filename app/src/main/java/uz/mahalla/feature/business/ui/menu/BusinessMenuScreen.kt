package uz.mahalla.feature.business.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.MahallaBottomSheet
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaCheckboxRow
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaSegmentedControl
import uz.mahalla.core.ui.components.MahallaSnackbarHost
import uz.mahalla.core.ui.components.MahallaSwitchRow
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.components.rememberSnackbarController
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.business.domain.BusinessMenu
import uz.mahalla.feature.business.domain.BusinessMenuItem
import uz.mahalla.feature.business.domain.BusinessMenuSection
import uz.mahalla.feature.business.domain.NewMenuItemError
import uz.mahalla.feature.business.domain.NewMenuItemForm
import uz.mahalla.feature.business.ui.BusinessInlineFailure
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Меню и стоп-лист (задача 12.4).
 *
 * Стоп-лист — главное действие экрана и потому переключатель прямо в строке:
 * «кончился фарш» случается посреди обеда, и открывать ради этого карточку
 * позиции значит потерять минуту там, где счёт на секунды.
 */
@Composable
fun BusinessMenuScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BusinessMenuViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarController()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(BusinessMenuEvent.ScreenResumed)
    }

    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is BusinessMenuEffect.ItemCreated -> snackbar.show(
                    text = context.getString(R.string.business_menu_created, effect.name),
                    tone = MahallaTone.Success,
                )

                is BusinessMenuEffect.StopListChanged -> snackbar.show(
                    text = context.getString(
                        if (effect.stopped) {
                            R.string.business_menu_stopped
                        } else {
                            R.string.business_menu_resumed
                        },
                        effect.name,
                    ),
                    tone = if (effect.stopped) MahallaTone.Warning else MahallaTone.Success,
                )
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        BusinessMenuContentScreen(
            state = state,
            onEvent = viewModel::onEvent,
            onBack = onBack,
        )
        MahallaSnackbarHost(
            controller = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun BusinessMenuContentScreen(
    state: BusinessMenuState,
    onEvent: (BusinessMenuEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = state.placeName.takeIf(String::isNotBlank)
                ?: stringResource(R.string.business_section_menu),
            onBack = onBack,
        )
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(BusinessMenuEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                if (state.stoppedCount > 0) {
                    item(key = "stopped-count") {
                        Text(
                            text = stringResource(
                                R.string.business_menu_stopped_count,
                                state.stoppedCount,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalMahallaColors.current.fgMuted,
                        )
                    }
                }
                state.actionFailure?.let { failure ->
                    item(key = "action-failure") { BusinessInlineFailure(failure = failure) }
                }
                menuItems(state = state, onEvent = onEvent)
            }
        }
    }

    if (state.isFormVisible) {
        NewMenuItemSheet(state = state, onEvent = onEvent)
    }
}

private fun LazyListScope.menuItems(
    state: BusinessMenuState,
    onEvent: (BusinessMenuEvent) -> Unit,
) {
    when (val menu = state.menu) {
        is ScreenState.Loading -> item(key = "loading") { ListSkeleton(itemCount = 4) }

        // Пусто — это «нет разделов», а не «нет блюд»: `CreateItemRequest`
        // требует `menuId`, а завести раздел из приложения нечем. Кнопки
        // «добавить» тут поэтому и нет — она вела бы в форму без раздела.
        is ScreenState.Empty -> item(key = "empty") {
            EmptyState(
                title = stringResource(R.string.business_menu_empty_title),
                description = stringResource(R.string.business_menu_no_sections),
                icon = Icons.Outlined.MenuBook,
            )
        }

        is ScreenState.Error -> item(key = "error") {
            BusinessInlineFailure(
                failure = menu.failure,
                onRetry = { onEvent(BusinessMenuEvent.Retry) },
            )
        }

        is ScreenState.Content -> {
            // Разделы есть, а блюд ещё нет — первый день заведения. Разделы при
            // этом остаются на экране: именно в них и кладут первую позицию.
            if (menu.data.sections.all { it.items.isEmpty() }) {
                item(key = "no-items") {
                    Text(
                        text = stringResource(R.string.business_menu_empty_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalMahallaColors.current.fgMuted,
                    )
                }
            }
            menu.data.sections.forEach { section ->
                item(key = "section-${section.id}") {
                    SectionHeader(
                        title = section.name.takeIf(String::isNotBlank)
                            ?: stringResource(R.string.business_menu_unnamed_section),
                    )
                }
                items(section.items, key = BusinessMenuItem::id) { item ->
                    MenuItemCard(
                        item = item,
                        pending = state.pendingItemId == item.id,
                        enabled = !state.isBusy,
                        onEvent = onEvent,
                    )
                }
            }
            item(key = "add") {
                MahallaButton(
                    text = stringResource(R.string.business_menu_add),
                    onClick = { onEvent(BusinessMenuEvent.AddItemClicked) },
                    variant = MahallaButtonVariant.Secondary,
                    state = ButtonState(
                        enabled = !state.isBusy && state.sectionIds.isNotEmpty(),
                    ),
                )
            }
        }
    }
}

/** Строка меню: название, цена, халяль-бейдж и переключатель стоп-листа. */
@Composable
private fun MenuItemCard(
    item: BusinessMenuItem,
    pending: Boolean,
    enabled: Boolean,
    onEvent: (BusinessMenuEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    MahallaCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.name.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.business_menu_unnamed_item),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (item.isHalal) {
                MahallaBadge(
                    text = stringResource(R.string.business_menu_halal),
                    tone = MahallaTone.Success,
                )
            }
        }

        Text(
            text = MoneyFormatter.withCurrency(
                item.priceSum,
                stringResource(R.string.currency_uzs),
            ),
            modifier = Modifier.padding(top = Spacing.item),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.fgMuted,
        )

        item.description?.let { description ->
            Text(
                text = description,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }

        MahallaSwitchRow(
            title = stringResource(R.string.business_menu_in_sale),
            checked = item.isAvailable,
            onCheckedChange = { onEvent(BusinessMenuEvent.StopListToggled(item.id)) },
            description = stringResource(R.string.business_menu_in_sale_description),
            // Пока идёт запрос, переключатель занят: ручка ничего не
            // возвращает, и два переворота подряд разошлись бы с сервером.
            enabled = enabled && !pending,
        )
    }
}

/**
 * Форма новой позиции. В шторке, а не отдельным экраном: полей пять, и ради
 * них не стоит терять из виду меню, куда позиция ляжет.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewMenuItemSheet(
    state: BusinessMenuState,
    onEvent: (BusinessMenuEvent) -> Unit,
) {
    val sections = (state.menu as? ScreenState.Content)?.data?.sections.orEmpty()
    MahallaBottomSheet(
        onDismiss = { onEvent(BusinessMenuEvent.FormDismissed) },
        title = stringResource(R.string.business_menu_add),
    ) {
        // Выбор раздела показываем только когда их несколько: сегмент из
        // одной кнопки — это вопрос без вариантов ответа.
        if (sections.size > 1) {
            Text(
                text = stringResource(R.string.business_menu_section),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
            MahallaSegmentedControl(
                options = sections.map { section ->
                    section.name.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.business_menu_unnamed_section)
                },
                selectedIndex = sections.indexOfFirst { it.id == state.form.sectionId }
                    .coerceAtLeast(0),
                onSelect = { index ->
                    sections.getOrNull(index)?.let {
                        onEvent(BusinessMenuEvent.SectionSelected(it.id))
                    }
                },
                enabled = !state.isSaving,
            )
        }

        MahallaTextField(
            value = state.form.name,
            onValueChange = { onEvent(BusinessMenuEvent.NameChanged(it)) },
            label = stringResource(R.string.business_menu_field_name),
            errorText = state.nameError(),
            enabled = !state.isSaving,
        )

        MahallaTextField(
            value = state.form.priceText,
            onValueChange = { onEvent(BusinessMenuEvent.PriceChanged(it)) },
            label = stringResource(R.string.business_menu_field_price),
            supportingText = stringResource(
                R.string.business_menu_field_price_hint,
                MoneyFormatter.amount(NewMenuItemForm.MIN_PRICE_SUM),
            ),
            errorText = state.priceError(),
            enabled = !state.isSaving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        MahallaTextField(
            value = state.form.description,
            onValueChange = { onEvent(BusinessMenuEvent.DescriptionChanged(it)) },
            label = stringResource(R.string.business_menu_field_description),
            errorText = state.descriptionError(),
            enabled = !state.isSaving,
            singleLine = false,
        )

        MahallaTextField(
            value = state.form.prepMinutesText,
            onValueChange = { onEvent(BusinessMenuEvent.PrepMinutesChanged(it)) },
            label = stringResource(R.string.business_menu_field_prep),
            errorText = state.prepError(),
            enabled = !state.isSaving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        MahallaCheckboxRow(
            title = stringResource(R.string.business_menu_halal),
            checked = state.form.isHalal,
            onCheckedChange = { onEvent(BusinessMenuEvent.HalalChanged(it)) },
            enabled = !state.isSaving,
        )

        state.formFailure?.let { failure ->
            BusinessInlineFailure(failure = failure)
        }

        MahallaButton(
            text = stringResource(R.string.business_menu_save),
            onClick = { onEvent(BusinessMenuEvent.SaveClicked) },
            state = ButtonState(loading = state.isSaving),
        )
    }
}

/**
 * Замечания к полям. Каждое поле спрашивает про свои ошибки само — общий
 * список под формой заставлял бы искать, к чему относится фраза.
 */
@Composable
private fun BusinessMenuState.nameError(): String? = when {
    hasError(NewMenuItemError.NameRequired) ->
        stringResource(R.string.business_menu_error_name_required)

    hasError(NewMenuItemError.NameTooLong(NewMenuItemForm.MAX_NAME_LENGTH)) ->
        pluralStringResource(
            R.plurals.business_menu_error_too_long,
            NewMenuItemForm.MAX_NAME_LENGTH,
            NewMenuItemForm.MAX_NAME_LENGTH,
        )

    else -> null
}

@Composable
private fun BusinessMenuState.priceError(): String? = when {
    hasError(NewMenuItemError.PriceRequired) ->
        stringResource(R.string.business_menu_error_price_required)

    hasError(NewMenuItemError.PriceNotANumber) ->
        stringResource(R.string.business_menu_error_price_number)

    hasError(NewMenuItemError.PriceTooSmall(NewMenuItemForm.MIN_PRICE_SUM)) -> stringResource(
        R.string.business_menu_error_price_small,
        MoneyFormatter.amount(NewMenuItemForm.MIN_PRICE_SUM),
    )

    hasError(NewMenuItemError.PriceTooLarge(NewMenuItemForm.MAX_PRICE_SUM)) -> stringResource(
        R.string.business_menu_error_price_large,
        MoneyFormatter.amount(NewMenuItemForm.MAX_PRICE_SUM),
    )

    else -> null
}

@Composable
private fun BusinessMenuState.descriptionError(): String? =
    if (hasError(NewMenuItemError.DescriptionTooLong(NewMenuItemForm.MAX_DESCRIPTION_LENGTH))) {
        pluralStringResource(
            R.plurals.business_menu_error_too_long,
            NewMenuItemForm.MAX_DESCRIPTION_LENGTH,
            NewMenuItemForm.MAX_DESCRIPTION_LENGTH,
        )
    } else {
        null
    }

@Composable
private fun BusinessMenuState.prepError(): String? = when {
    hasError(NewMenuItemError.PrepMinutesNotANumber) ->
        stringResource(R.string.business_menu_error_prep_number)

    hasError(NewMenuItemError.PrepMinutesTooLarge(NewMenuItemForm.MAX_PREP_MINUTES)) ->
        pluralStringResource(
            R.plurals.business_menu_error_prep_large,
            NewMenuItemForm.MAX_PREP_MINUTES,
            NewMenuItemForm.MAX_PREP_MINUTES,
        )

    else -> null
}

@ThemeLanguagePreviews
@Composable
private fun BusinessMenuScreenPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        BusinessMenuContentScreen(
            state = BusinessMenuState(
                placeName = "Osh Markazi",
                menu = ScreenState.Content(
                    BusinessMenu(
                        sections = listOf(
                            BusinessMenuSection(
                                id = "s-1",
                                name = "Issiq taomlar",
                                items = listOf(
                                    BusinessMenuItem(
                                        id = "i-1",
                                        name = "Osh",
                                        priceSum = 32_000,
                                        isHalal = true,
                                    ),
                                    BusinessMenuItem(
                                        id = "i-2",
                                        name = "Lag'mon",
                                        priceSum = 28_000,
                                        isAvailable = false,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
