package uz.mahalla.feature.pharmacy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocalPharmacy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.LoadMoreAuto
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaBottomSheet
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaIconButton
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaSearchField
import uz.mahalla.core.ui.components.MahallaSwitchRow
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.userMessage
import uz.mahalla.feature.pharmacy.domain.PharmacyProduct
import uz.mahalla.feature.pharmacy.domain.ProductStock
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums

/**
 * Витрина аптеки (issue #100): что есть в наличии и почём.
 *
 * Кнопки «купить» нет намеренно — заказать товар аптеки бэкенду сейчас нечем
 * (см. `PharmacyViewModel`). Экран отвечает на один вопрос: есть ли это
 * лекарство здесь.
 */
@Composable
fun PharmacyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PharmacyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    PharmacyContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun PharmacyContent(
    state: PharmacyState,
    onEvent: (PharmacyEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = state.placeName.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.pharmacy_title),
            onBack = onBack,
        )

        // Поиск живёт над списком, а не внутри него: он относится ко всей
        // витрине, и уезжать вместе с прокруткой ему незачем.
        MahallaSearchField(
            query = state.query,
            onQueryChange = { onEvent(PharmacyEvent.QueryChanged(it)) },
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.item),
            placeholder = stringResource(R.string.pharmacy_search_hint),
            onSearch = { onEvent(PharmacyEvent.QuerySubmitted) },
        )

        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(PharmacyEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.gutter,
                    end = Spacing.gutter,
                    bottom = Spacing.gutter,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                // Владелец/менеджер (issue #252) — кнопка над списком, а не
                // внутри `productItems`: она не зависит от того, загружен ли
                // список, пуст он или упал с ошибкой.
                if (state.isOwner) {
                    item(key = "owner-add") {
                        MahallaButton(
                            text = stringResource(R.string.pharmacy_add_product),
                            onClick = { onEvent(PharmacyEvent.AddProductClicked) },
                            variant = MahallaButtonVariant.Secondary,
                            icon = Icons.Outlined.Add,
                        )
                    }
                }
                productItems(state = state, onEvent = onEvent)
            }
        }
    }

    state.createForm?.let { form -> NewProductSheet(form = form, onEvent = onEvent) }
    state.stockForm?.let { form -> StockEditSheet(form = form, onEvent = onEvent) }
}

/**
 * Состояния разложены руками, а не через `ScreenStateHost`: тот рисует
 * `ApiErrorState` с собственной прокруткой, а внутри `LazyColumn` вложенная
 * прокрутка меряется бесконечной высотой и роняет измерение (issue #62).
 */
private fun LazyListScope.productItems(
    state: PharmacyState,
    onEvent: (PharmacyEvent) -> Unit,
) {
    when (val products = state.products) {
        is ScreenState.Loading -> item(key = "loading") {
            ListSkeleton(itemCount = LIST_SKELETONS)
        }

        // Пустая витрина и пустой поиск — разные сообщения: второе на месте
        // первого читается как поломка поиска.
        is ScreenState.Empty -> item(key = "empty") {
            EmptyState(
                title = if (state.searchedQuery.isBlank()) {
                    stringResource(R.string.pharmacy_empty_title)
                } else {
                    stringResource(R.string.pharmacy_not_found_title)
                },
                description = if (state.searchedQuery.isBlank()) {
                    stringResource(R.string.pharmacy_empty_description)
                } else {
                    stringResource(R.string.pharmacy_not_found_description, state.searchedQuery)
                },
                icon = Icons.Outlined.LocalPharmacy,
            )
        }

        is ScreenState.Error -> item(key = "error") {
            InlineFailure(
                failure = products.failure,
                onRetry = { onEvent(PharmacyEvent.Retry) },
            )
        }

        is ScreenState.Content -> {
            items(products.data, key = PharmacyProduct::id) { product ->
                ProductCard(
                    product = product,
                    isOwner = state.isOwner,
                    onEditStock = { onEvent(PharmacyEvent.StockEditClicked(product)) },
                )
            }
            if (state.hasMore || state.loadMoreFailure != null) {
                item(key = "load-more") {
                    LoadMoreAuto(
                        itemCount = products.data.size,
                        isLoading = state.isLoadingMore,
                        failure = state.loadMoreFailure,
                        onLoadMore = { onEvent(PharmacyEvent.LoadMore) },
                    )
                }
            }
        }
    }
}

/**
 * Карточка товара. Наличие — бейджем в той же строке, что и название:
 * ради него аптеку и открывают, и прятать его под ценой значило бы заставить
 * читать всю карточку ради одного слова.
 *
 * Товара нет — карточка приглушается целиком: цена того, чего нет, ничего не
 * решает, а одинаковый вид с доступным товаром читается как «есть».
 */
@Composable
private fun ProductCard(
    product: PharmacyProduct,
    modifier: Modifier = Modifier,
    isOwner: Boolean = false,
    onEditStock: () -> Unit = {},
) {
    val colors = LocalMahallaColors.current
    val absent = product.stock == ProductStock.OutOfStock
    val titleColor = if (absent) colors.fgMuted else MaterialTheme.colorScheme.onSurface

    MahallaCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = product.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
            )
            MahallaBadge(
                text = stringResource(product.stock.labelRes()),
                tone = product.stock.tone(),
            )
            // Владелец/менеджер (issue #252): остаток правит тот же товар, что
            // виден на витрине, поэтому иконка живёт прямо рядом с бейджем
            // наличия, а не в отдельном разделе.
            if (isOwner) {
                MahallaIconButton(
                    icon = Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.pharmacy_edit_stock_action),
                    onClick = onEditStock,
                )
            }
        }

        // Форма выпуска и дозировка — подпись под названием: «tabletka, 500 mg»
        // отличает одно лекарство от другого вернее, чем производитель.
        product.formLabel?.let { form ->
            Text(
                text = form,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
        }

        product.manufacturer?.let { manufacturer ->
            Text(
                text = manufacturer,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }

        product.priceSum?.let { price ->
            Text(
                text = MoneyFormatter.withCurrency(price, stringResource(R.string.currency_uzs)),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.titleSmall.merge(TabularNums),
                color = titleColor,
            )
        }

        // «Осталось 2» — повод поспешить; покупателю число называется только
        // когда товар кончается, иначе витрина читалась бы как складская
        // сводка (issue #94/#100).
        if (product.showsStockQuantity) {
            val left = product.stockQuantity ?: 0
            Text(
                text = pluralStringResource(R.plurals.pharmacy_stock_left, left, left),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall.merge(TabularNums),
                color = colors.warning,
            )
        } else if (isOwner && product.stockQuantity != null) {
            // Владельцу (issue #252) остаток нужен всегда — это его склад, а
            // не повод поторопиться, поэтому нейтральная подпись, а не
            // строка «осталось N», рассчитанная на срочность у покупателя.
            Text(
                text = stringResource(R.string.pharmacy_stock_owner_count, product.stockQuantity),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall.merge(TabularNums),
                color = colors.fgMuted,
            )
        }

        // Рецепт — не запрет, а предупреждение: съездить в аптеку без рецепта
        // и узнать об этом на месте обиднее, чем прочитать здесь.
        if (product.requiresPrescription) {
            Text(
                text = stringResource(R.string.pharmacy_prescription_required),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }
    }
}

/**
 * Новый товар (issue #252). Ошибки полей показываются только после первой
 * попытки сохранить (`submitAttempted`) — форма стартует пустой, и это не то
 * же самое, что правка уже заполненной карточки места в issue #188.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewProductSheet(
    form: NewProductFormState,
    onEvent: (PharmacyEvent) -> Unit,
) {
    val draft = form.draft
    val showErrors = form.submitAttempted
    MahallaBottomSheet(
        onDismiss = { onEvent(PharmacyEvent.CreateFormDismissed) },
        title = stringResource(R.string.pharmacy_new_product_title),
    ) {
        MahallaTextField(
            value = draft.name,
            onValueChange = { onEvent(PharmacyEvent.CreateNameChanged(it)) },
            label = stringResource(R.string.pharmacy_field_name),
            errorText = if (showErrors && !draft.isNameValid) {
                stringResource(R.string.pharmacy_field_name_invalid)
            } else {
                null
            },
            enabled = !form.submitting,
        )
        MahallaTextField(
            value = draft.priceText,
            onValueChange = { onEvent(PharmacyEvent.CreatePriceChanged(it)) },
            label = stringResource(R.string.pharmacy_field_price),
            errorText = if (showErrors && !draft.isPriceValid) {
                stringResource(R.string.pharmacy_field_price_invalid)
            } else {
                null
            },
            enabled = !form.submitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        MahallaTextField(
            value = draft.stockText,
            onValueChange = { onEvent(PharmacyEvent.CreateStockChanged(it)) },
            label = stringResource(R.string.pharmacy_field_stock),
            errorText = if (showErrors && !draft.isStockValid) {
                stringResource(R.string.pharmacy_field_stock_invalid)
            } else {
                null
            },
            enabled = !form.submitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        MahallaTextField(
            value = draft.manufacturer,
            onValueChange = { onEvent(PharmacyEvent.CreateManufacturerChanged(it)) },
            label = stringResource(R.string.pharmacy_field_manufacturer),
            enabled = !form.submitting,
        )
        MahallaTextField(
            value = draft.dosageForm,
            onValueChange = { onEvent(PharmacyEvent.CreateDosageFormChanged(it)) },
            label = stringResource(R.string.pharmacy_field_dosage_form),
            enabled = !form.submitting,
        )
        MahallaTextField(
            value = draft.strength,
            onValueChange = { onEvent(PharmacyEvent.CreateStrengthChanged(it)) },
            label = stringResource(R.string.pharmacy_field_strength),
            enabled = !form.submitting,
        )
        MahallaTextField(
            value = draft.description,
            onValueChange = { onEvent(PharmacyEvent.CreateDescriptionChanged(it)) },
            label = stringResource(R.string.pharmacy_field_description),
            enabled = !form.submitting,
            singleLine = false,
        )
        MahallaSwitchRow(
            title = stringResource(R.string.pharmacy_field_prescription),
            checked = draft.requiresPrescription,
            onCheckedChange = { onEvent(PharmacyEvent.CreatePrescriptionChanged(it)) },
            enabled = !form.submitting,
        )
        form.failure?.let { failure -> InlineFailure(failure = failure) }
        MahallaButton(
            text = stringResource(R.string.pharmacy_create_submit),
            onClick = { onEvent(PharmacyEvent.CreateSubmitted) },
            state = ButtonState(
                enabled = !showErrors || draft.canSubmit,
                loading = form.submitting,
            ),
        )
    }
}

/**
 * Остаток одного товара (issue #252, `PUT products/{id}/stock`). Одно поле —
 * форма не переиспользует [NewProductSheet], у неё нет ничего общего, кроме
 * шторки.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StockEditSheet(
    form: StockEditFormState,
    onEvent: (PharmacyEvent) -> Unit,
) {
    MahallaBottomSheet(
        onDismiss = { onEvent(PharmacyEvent.StockFormDismissed) },
        title = form.productName.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.pharmacy_stock_edit_title),
    ) {
        MahallaTextField(
            value = form.quantityText,
            onValueChange = { onEvent(PharmacyEvent.StockQuantityChanged(it)) },
            label = stringResource(R.string.pharmacy_stock_edit_label),
            errorText = if (form.submitAttempted && !form.isQuantityValid) {
                stringResource(R.string.pharmacy_field_stock_invalid)
            } else {
                null
            },
            enabled = !form.submitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        form.failure?.let { failure -> InlineFailure(failure = failure) }
        MahallaButton(
            text = stringResource(R.string.pharmacy_stock_edit_submit),
            onClick = { onEvent(PharmacyEvent.StockSubmitted) },
            state = ButtonState(
                enabled = !form.submitAttempted || form.isQuantityValid,
                loading = form.submitting,
            ),
        )
    }
}

/**
 * Отказ внутри экрана: текст сервера, подробности и — если есть чем — повтор
 * (issue #34). `ApiErrorState` здесь не годится: он несёт собственную
 * прокрутку, а живёт этот блок внутри `LazyColumn`.
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

/** Подписи наличия: домен знает состояние, ресурсы — формулировку. */
private fun ProductStock.labelRes(): Int = when (this) {
    ProductStock.InStock -> R.string.pharmacy_stock_in
    ProductStock.OutOfStock -> R.string.pharmacy_stock_out
    ProductStock.Unknown -> R.string.pharmacy_stock_unknown
}

/**
 * «Нет в наличии» — не сбой приложения, но именно та новость, ради которой
 * сюда пришли: её видно красным. «Неизвестно» нейтрально — пугать человека
 * молчанием сервера незачем.
 */
private fun ProductStock.tone(): MahallaTone = when (this) {
    ProductStock.InStock -> MahallaTone.Success
    ProductStock.OutOfStock -> MahallaTone.Error
    ProductStock.Unknown -> MahallaTone.Neutral
}

private const val LIST_SKELETONS = 4

@ThemeLanguagePreviews
@Composable
private fun PharmacyScreenPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        PharmacyContent(
            state = PharmacyState(
                placeName = "Dori-Darmon",
                products = ScreenState.Content(
                    listOf(
                        PharmacyProduct(
                            id = "p-1",
                            name = "Paratsetamol",
                            manufacturer = "Uzpharm",
                            dosageForm = "tabletka",
                            strength = "500 mg",
                            priceSum = 12_000,
                            stockQuantity = 2,
                            stock = ProductStock.InStock,
                        ),
                        PharmacyProduct(
                            id = "p-2",
                            name = "Amoksitsillin",
                            dosageForm = "kapsula",
                            strength = "250 mg",
                            priceSum = 34_500,
                            stockQuantity = 0,
                            stock = ProductStock.OutOfStock,
                            requiresPrescription = true,
                        ),
                        PharmacyProduct(
                            id = "p-3",
                            name = "Askorbin kislotasi",
                            priceSum = 5_000,
                            stock = ProductStock.Unknown,
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun PharmacyOwnerScreenPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        PharmacyContent(
            state = PharmacyState(
                placeName = "Dori-Darmon",
                isOwner = true,
                products = ScreenState.Content(
                    listOf(
                        PharmacyProduct(
                            id = "p-1",
                            name = "Paratsetamol",
                            manufacturer = "Uzpharm",
                            priceSum = 12_000,
                            stockQuantity = 340,
                            stock = ProductStock.InStock,
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun PharmacyNewProductSheetPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        NewProductSheet(form = NewProductFormState(), onEvent = {})
    }
}
