package uz.mahalla.navigation

import kotlinx.serialization.Serializable
import uz.mahalla.feature.auth.domain.OtpChallenge
import uz.mahalla.feature.auth.domain.OtpDeliveryChannel
import uz.mahalla.feature.booking.domain.AppointmentVertical

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
 * Выбор точки на карте (issue #90): открывается поверх формы, где нужны
 * координаты, и возвращает выбранную точку обратно тому экрану, который его
 * позвал.
 *
 * @param point точка, выбранная в прошлый раз, — с неё начинается карта.
 * Едет строкой (`MapPoint.encode`), потому что типизированные маршруты кладут
 * аргументы в `Bundle`, и ради пары дробных чисел понадобился бы собственный
 * `NavType`; то же решение, что у канала доставки кода в [OtpRoute].
 */
@Serializable
data class MapPickerRoute(val point: String? = null)

/**
 * Имена аргументов [MapPickerRoute] и ключ результата.
 *
 * Результат возвращается через `SavedStateHandle` предыдущей записи стека:
 * экран выбора не знает, кто его позвал, и не должен — точку одинаково ждут и
 * анкета продавца, и любой следующий экран с адресом.
 */
object MapPickerArgs {
    const val POINT = "point"

    /** Ключ выбранной точки в `savedStateHandle` вызвавшего экрана. */
    const val RESULT_POINT = "map_picker_point"
}

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

/**
 * «Мои заведения» со статусом модерации (issue #94). Вне обоих графов, как
 * центр уведомлений: открывается строкой из профиля, а возврат ведёт туда же.
 *
 * Аргументов нет: список грузится целиком, а конкретное заведение ведёт на
 * свою карточку в каталоге ([PlaceRoute]).
 */
@Serializable
data object MyPlacesRoute

/**
 * Подписка (issue #103, эпик #13): тарифы и то, что оформлено сейчас. Вне
 * обоих графов, как «мои заведения»: открывается строкой из профиля, возврат
 * ведёт туда же.
 *
 * Аргументов нет: и набор тарифов, и текущая подписка приезжают с сервера — их
 * незачем передавать маршрутом, а пережив смерть процесса, они бы устарели.
 */
@Serializable
data object SubscriptionRoute

// --- Детали ---

@Serializable
data class PlaceRoute(val placeId: String)

// --- Вертикаль «Очередь» (эпик #10, issue #96) ---

/**
 * Электронная очередь заведения: взять талон и следить за ним.
 *
 * @param placeName название заведения. Едет маршрутом по той же причине, что
 * и у [MenuRoute]: ответ `walkin/send` его не содержит, а талон без имени
 * места читается как чужой.
 */
@Serializable
data class QueueRoute(
    val placeId: String,
    val placeName: String = "",
)

// --- Вертикаль «Бронь» (эпик #11, issue #97) ---

/**
 * Запись на время: услуга → день → слот → подтверждение.
 *
 * @param placeName название заведения. Едет маршрутом по той же причине, что и
 * у [QueueRoute] и [MenuRoute]: ни в ответе `barber-services`, ни в
 * `AppointmentResponse` его нет, а шапка без имени места читается как чужая.
 */
@Serializable
data class BookingRoute(
    val placeId: String,
    val placeName: String = "",
)

/**
 * «Мои записи» (issue #97). Вне обоих графов, как центр уведомлений:
 * открывается строкой из профиля и с экрана подтверждения записи, а возврат
 * ведёт туда, откуда пришли.
 *
 * @param vertical к мастеру или к врачу — имя константы
 * [uz.mahalla.feature.booking.domain.AppointmentVertical], а не само
 * перечисление: типизированные маршруты кладут аргументы в `Bundle`, и для
 * enum понадобился бы собственный `NavType` (то же решение, что у канала
 * доставки кода в [OtpRoute] и у точки на карте в [MapPickerRoute]).
 *
 * Экран один на обе вертикали, а не два одинаковых: у бэкенда это одна модель
 * записи и одна ручка отмены, а две копии разошлись бы при первой же правке
 * (то же решение, что у [RoleRoute] в issue #84). Списки при этом разные —
 * `appointments/my` и `hospitals/appointments/my`.
 *
 * Других аргументов нет: список грузится с сервера целиком, а конкретная
 * запись никуда не ведёт — своего экрана у неё нет.
 */
@Serializable
data class MyAppointmentsRoute(
    val vertical: String = AppointmentVertical.Barber.name,
)

/**
 * Имена аргументов [MyAppointmentsRoute] — ViewModel читает их из
 * `SavedStateHandle` напрямую, как [OtpArgs]: `toRoute()` разбирает маршрут
 * через настоящий `Bundle`, а в JVM-тестах android.jar заглушен и все
 * аргументы молча читаются как `null`. Совпадение имён с полями маршрута
 * проверяет `RoutesSerializationTest`.
 */
object MyAppointmentsArgs {
    const val VERTICAL = "vertical"
}

// --- Вертикаль «Мастера» (issue #107) ---

/**
 * Каталог мастеров-фрилансеров. Вне обоих графов, как поиск и карта:
 * открывается строкой с главной, а возврат ведёт обратно туда же.
 *
 * Мастер — **не заведение**: у него свой каталог (`freelancers`), свои услуги
 * и свои заказы, отдельно от `places`, — поэтому и маршрут свой, а не
 * [SearchRoute] с категорией.
 */
@Serializable
data object FreelancersRoute

/**
 * Профиль мастера и заказ услуги.
 *
 * @param freelancerName имя из каталога. Едет маршрутом по той же причине, что
 * и название заведения в [BookingRoute]: шапка рисуется раньше, чем приезжает
 * профиль, а пустой заголовок читается как чужой экран. Как только профиль
 * загрузится, имя берётся из ответа сервера — оно точнее.
 */
@Serializable
data class FreelancerRoute(
    val freelancerId: String,
    val freelancerName: String = "",
)

/**
 * «Мои заказы у мастеров». Вне обоих графов, как центр уведомлений:
 * открывается строкой из профиля и с экрана подтверждения заказа.
 *
 * Аргументов нет: список грузится с сервера целиком, а конкретный заказ никуда
 * не ведёт — своего экрана у него нет, отменять его клиенту нечем.
 */
@Serializable
data object MyFreelancerOrdersRoute

// --- Вертикаль «Больницы» (эпик #11, issue #99) ---

/**
 * Запись к врачу: врач → день → время → жалоба → подтверждение.
 *
 * Отдельный маршрут, а не [BookingRoute] с флагом: у больниц другой список
 * (врачи, а не услуги), другое тело запроса (`doctorId` вместо `serviceId`) и
 * лишнее поле жалобы — общий экран пришлось бы ветвить на каждом шаге.
 *
 * @param placeName название заведения. Едет маршрутом по той же причине, что и
 * у [BookingRoute]: ни в ответе `hospitals/.../doctors`, ни в
 * `AppointmentResponse` его нет, а шапка без имени места читается как чужая.
 */
@Serializable
data class DoctorBookingRoute(
    val placeId: String,
    val placeName: String = "",
)

// --- Вертикаль «Одежда» (issue #108): каталог → товар → корзина → заказ ---

/**
 * Витрина магазина: категории и товары.
 *
 * @param placeName название магазина. Едет маршрутом по той же причине, что и
 * у [MenuRoute]: в `CatalogResponse` его нет, а витрина без имени магазина
 * читается как чужая.
 */
@Serializable
data class FashionCatalogRoute(
    val placeId: String,
    val placeName: String = "",
)

/**
 * Карточка товара: цвет, размер, «в корзину».
 *
 * Знает только `productId` — всё остальное, включая `storeId`, приезжает в
 * ответе `fashion/products/{id}`.
 */
@Serializable
data class FashionProductRoute(val productId: String)

/**
 * Корзина. Аргументов нет: она живёт **на сервере** и одна на все магазины
 * (issue #108) — открывается одинаково с любого экрана вертикали.
 */
@Serializable
data object FashionCartRoute

/**
 * Оформление заказа по одному магазину.
 *
 * `storeId` обязателен: `PlaceOrderRequest` принимает ровно один `placeId`, а
 * серверная корзина общая — заказ собирается из строк только этого магазина.
 */
@Serializable
data class FashionCheckoutRoute(val storeId: String)

/**
 * «Мои заказы одежды». Вне обоих графов, как «мои записи»: открывается строкой
 * из профиля и с экрана подтверждения заказа.
 */
@Serializable
data object FashionOrdersRoute

/**
 * Имена аргументов маршрутов вертикали «Одежда» — ViewModel'и читают их из
 * `SavedStateHandle` напрямую, как [OtpArgs]: `toRoute()` разбирает маршрут
 * через настоящий `Bundle`, а в JVM-тестах android.jar заглушен и все
 * аргументы молча читаются как `null`. Совпадение имён с полями маршрутов
 * проверяет `RoutesSerializationTest`.
 */
object FashionArgs {
    const val PLACE_ID = "placeId"
    const val PLACE_NAME = "placeName"
    const val PRODUCT_ID = "productId"
    const val STORE_ID = "storeId"
}

// --- Вертикаль «Еда» (эпик 5): меню → корзина → checkout → статус ---

@Serializable
data class MenuRoute(
    val placeId: String,
    /**
     * Название заведения. Едет маршрутом, потому что ответ `food/.../menu` его
     * не содержит: без него шапка меню, корзина и диалог «корзина другого
     * заведения» остались бы без имени.
     */
    val placeName: String = "",
)

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
