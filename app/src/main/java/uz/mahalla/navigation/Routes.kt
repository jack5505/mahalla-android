package uz.mahalla.navigation

import kotlinx.serialization.Serializable
import uz.mahalla.feature.auth.domain.OtpChallenge

/**
 * Typed routes навигации (эпик 1.2). Маршрут — это `@Serializable`-класс, а не
 * строка: аргументы типизированы, опечатка в имени параметра не доживает до
 * рантайма.
 *
 * Имена полей одновременно являются именами аргументов в deep link'ах
 * (см. [DeepLinks]) — это проверяется тестом `RoutesSerializationTest`.
 */

// --- Графы ---

@Serializable
data object OnboardingGraph

@Serializable
data object MainGraph

// --- Онбординг: welcome → phone → otp → pin → biometric → geo ---

@Serializable
data object WelcomeRoute

@Serializable
data object PhoneRoute

/**
 * Экран ввода кода. Параметры испытания приходят с экрана телефона (сервер
 * сообщает их в ответе на запрос кода), иначе таймер повтора и длина кода на
 * экране OTP были бы выдуманными.
 */
@Serializable
data class OtpRoute(
    val phone: String,
    val resendAfterSeconds: Int = OtpChallenge.DEFAULT_RESEND_SECONDS,
    val codeLength: Int = OtpChallenge.DEFAULT_CODE_LENGTH,
)

/**
 * Имена аргументов [OtpRoute] для чтения из `SavedStateHandle`. Совпадение с
 * полями маршрута проверяет `RoutesSerializationTest` — опечатка здесь иначе
 * ломала бы экран только в рантайме.
 */
object OtpArgs {
    const val PHONE = "phone"
    const val RESEND_AFTER_SECONDS = "resendAfterSeconds"
    const val CODE_LENGTH = "codeLength"
}

@Serializable
data object PinRoute

@Serializable
data object BiometricRoute

@Serializable
data object GeoRoute

// --- Основные разделы (bottom nav) ---

@Serializable
data object DiscoveryRoute

@Serializable
data object OrdersRoute

@Serializable
data object WalletRoute

@Serializable
data object ProfileRoute

// --- Discovery (эпик 4) ---

/**
 * Поиск с фильтрами. Оба аргумента необязательны: с главной сюда приходят
 * либо «просто поиск», либо поиск с предвыбранной категорией
 * ([PlaceCategory.apiValue][uz.mahalla.feature.discovery.domain.PlaceCategory]).
 */
@Serializable
data class SearchRoute(
    val categoryId: String? = null,
    val query: String? = null,
)

@Serializable
data object MapRoute

// --- Детали ---

@Serializable
data class PlaceRoute(val placeId: String)

// --- Вертикаль «Еда» (эпик 5): меню → корзина → checkout → статус ---

@Serializable
data class MenuRoute(val placeId: String)

@Serializable
data class CartRoute(val placeId: String)

@Serializable
data class CheckoutRoute(val placeId: String)

/**
 * Статус заказа. Экран достижим и сразу после оформления, и из списка заказов,
 * поэтому знает только id — всё остальное грузит сам.
 */
@Serializable
data class OrderStatusRoute(val orderId: String)
