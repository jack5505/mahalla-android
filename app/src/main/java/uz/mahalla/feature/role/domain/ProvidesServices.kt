package uz.mahalla.feature.role.domain

/**
 * Человек оказывает услуги: либо сам так сказал в анкете, либо у него уже
 * есть на это права на сервере (issue #244).
 *
 * Анкета и права — разные источники одного факта, и ни один не отменяет
 * другой: настоящий `FOOD_OWNER`, который анкету не заполнял, всё равно
 * оказывает услуги, а покупатель, заполнивший анкету продавца, — ещё нет,
 * пока сервер не выдаст ему роль. Отсюда «или», а не «и».
 *
 * Общее правило для «Мои заведения» ([uz.mahalla.feature.profile.ui.ProfileState.showMyPlaces])
 * и аудитории тарифов подписки — расхождение между ними и было issue #244.
 */
fun providesServices(formRole: UserRole?, serverRole: ServerRole): Boolean =
    formRole == UserRole.Provider || serverRole.isProvider
