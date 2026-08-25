package uz.mahalla.navigation

import kotlinx.serialization.Serializable

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

@Serializable
data class OtpRoute(val phone: String)

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

// --- Детали ---

@Serializable
data class PlaceRoute(val placeId: String)
