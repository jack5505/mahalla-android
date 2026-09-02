package uz.mahalla.navigation

import kotlinx.serialization.Serializable
import uz.mahalla.feature.auth.domain.OtpChallenge
import uz.mahalla.feature.auth.domain.OtpDeliveryChannel

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

/**
 * Ввод адреса бэкенда (issue #26). Лежит вне обоих графов: экран нужен и до
 * онбординга (первый запуск), и после него (сменить сервер из профиля), а
 * частью флоу входа он не является. Регистрируется только в сборках, которым
 * разрешено менять адрес (`BuildConfig.BACKEND_URL_OVERRIDE`).
 */
@Serializable
data object BackendUrlRoute

/**
 * Обновление приложения (issue #80). Тоже вне обоих графов: экран нужен и до
 * онбординга, и вместо основного графа — обязательное обновление не пускает
 * дальше вообще никуда.
 *
 * Аргументов нет намеренно: результат проверки версии живёт в памяти процесса
 * (`AppUpdateGate`), а в аргументах маршрута он попал бы в `SavedStateHandle` и
 * пережил бы смерть процесса — экран всплыл бы с данными проверки, которой в
 * этом запуске не было.
 */
@Serializable
data object UpdateRoute

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
    /** Токен отправленного кода: по нему бэкенд его и проверяет (issue #42). */
    val otpToken: String,
    val resendAfterSeconds: Int = OtpChallenge.DEFAULT_RESEND_SECONDS,
    val codeLength: Int = OtpChallenge.DEFAULT_CODE_LENGTH,
    /**
     * Канал доставки ([OtpDeliveryChannel]) именем константы, а не самим
     * перечислением: типизированные маршруты Navigation кладут аргументы в
     * `Bundle`, и для enum'а понадобился бы собственный `NavType`. Читает его
     * `OtpDeliveryChannel.byName`, незнакомое значение — SMS.
     */
    val channel: String = OtpDeliveryChannel.Sms.name,
)

/**
 * Имена аргументов [OtpRoute] для чтения из `SavedStateHandle`. Совпадение с
 * полями маршрута проверяет `RoutesSerializationTest` — опечатка здесь иначе
 * ломала бы экран только в рантайме.
 */
object OtpArgs {
    const val PHONE = "phone"
    const val OTP_TOKEN = "otpToken"
    const val RESEND_AFTER_SECONDS = "resendAfterSeconds"
    const val CODE_LENGTH = "codeLength"
    const val CHANNEL = "channel"
}

/**
 * Вход через Telegram-бот (issue #46). Аргументов нет намеренно: одноразовый
 * токен выдаёт бэкенд уже на самом экране и живёт он только в памяти
 * ViewModel — в аргументах маршрута он оказался бы в `SavedStateHandle`, то
 * есть пережил бы смерть процесса и попал в системный дамп состояния.
 */
@Serializable
data object TelegramRoute

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

/**
 * Центр уведомлений (issue #81). Вне графа табов, как поиск и карта: нижняя
 * навигация здесь не нужна, а возврат ведёт обратно на главную, откуда экран и
 * открывают иконкой в топбаре.
 *
 * Аргументов нет: список грузится целиком, а конкретное уведомление ведёт уже
 * на экран своей сущности.
 */
@Serializable
data object NotificationsRoute

// --- Анкеты покупателя и продавца (issue #84) ---

/**
 * «Кто вы»: покупатель или продавец, и дальше — своя анкета.
 *
 * Лежит вне обоих графов, как экран адреса бэкенда: экран нужен и в конце
 * онбординга, и потом из профиля (роль меняется — вчерашний покупатель
 * открывает кафе).
 *
 * @param onboarding экран открыт последним шагом регистрации. От этого
 * зависит, чем кончается заполнение анкеты: в онбординге — переходом в
 * приложение, из профиля — возвратом назад. Аргументом, а не отдельным
 * маршрутом: два одинаковых экрана в графе разошлись бы при первой же правке.
 */
@Serializable
data class RoleRoute(val onboarding: Boolean = false)

/** Анкета покупателя: имя, город, адрес по умолчанию. */
@Serializable
data class CustomerFormRoute(val onboarding: Boolean = false)

/** Анкета продавца: заявка на регистрацию заведения. */
@Serializable
data class ProviderFormRoute(val onboarding: Boolean = false)

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
