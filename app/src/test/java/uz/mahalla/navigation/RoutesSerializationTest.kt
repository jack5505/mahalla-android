package uz.mahalla.navigation

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.feature.booking.domain.AppointmentVertical

/**
 * Typed routes держатся на kotlinx.serialization: если маршрут перестанет
 * сериализоваться или поле переименуют — сломается навигация и deep link,
 * причём только в рантайме. Эти тесты ловят такое на сборке.
 *
 * Опт-ин осознанный: проверять состав полей маршрута можно только через
 * `descriptor`, а он экспериментальный. Двадцать предупреждений в логе сборки
 * прятали бы настоящие.
 */
@OptIn(ExperimentalSerializationApi::class)
class RoutesSerializationTest {

    private val json = Json

    @Test
    fun `routes with arguments round trip`() {
        val place = PlaceRoute(placeId = "p-42")
        assertEquals(place, json.decodeFromString<PlaceRoute>(json.encodeToString(place)))

        val otp = OtpRoute(phone = "+998901234567", otpToken = "otp-1")
        assertEquals(otp, json.decodeFromString<OtpRoute>(json.encodeToString(otp)))

        val search = SearchRoute(categoryId = "pharmacy", query = "osh")
        assertEquals(search, json.decodeFromString<SearchRoute>(json.encodeToString(search)))

        // Название заведения едет маршрутом: в ответе меню его нет, а корзина
        // и диалог «корзина другого заведения» без него безымянные.
        val menu = MenuRoute(placeId = "p-42", placeName = "Osh markazi")
        assertEquals(menu, json.decodeFromString<MenuRoute>(json.encodeToString(menu)))

        val order = OrderStatusRoute(orderId = "o-42")
        assertEquals(order, json.decodeFromString<OrderStatusRoute>(json.encodeToString(order)))

        // Анкеты (issue #84): флаг «пришли из регистрации» решает, чем
        // кончается заполнение, — терять его при сериализации нельзя.
        val role = RoleRoute(onboarding = true)
        assertEquals(role, json.decodeFromString<RoleRoute>(json.encodeToString(role)))
        assertEquals(RoleRoute(), json.decodeFromString<RoleRoute>(json.encodeToString(RoleRoute())))

        val customerForm = CustomerFormRoute(onboarding = true)
        assertEquals(
            customerForm,
            json.decodeFromString<CustomerFormRoute>(json.encodeToString(customerForm)),
        )

        val providerForm = ProviderFormRoute(onboarding = true)
        assertEquals(
            providerForm,
            json.decodeFromString<ProviderFormRoute>(json.encodeToString(providerForm)),
        )

        // Выбор точки на карте (issue #90): аргумент необязателен — карта
        // открывается и без ранее выбранной точки.
        val picker = MapPickerRoute(point = "41.311081,69.240562")
        assertEquals(picker, json.decodeFromString<MapPickerRoute>(json.encodeToString(picker)))
        assertEquals(
            MapPickerRoute(),
            json.decodeFromString<MapPickerRoute>(json.encodeToString(MapPickerRoute())),
        )
    }

    @Test
    fun `map picker argument is named the way its view model reads it`() {
        // ViewModel читает аргумент из `SavedStateHandle` по имени: опечатка
        // здесь означала бы карту, всегда открывающуюся заново.
        val descriptor = serializer<MapPickerRoute>().descriptor

        assertEquals(1, descriptor.elementsCount)
        assertEquals(MapPickerArgs.POINT, descriptor.getElementName(0))
    }

    /**
     * Вертикаль «Еда» (эпик 5): три экрана ходят по placeId, четвёртый — по
     * orderId. Совпадение имён аргументов важно, потому что ViewModel'и
     * читают их из `SavedStateHandle`.
     */
    @Test
    fun `food routes carry the ids their view models read`() {
        listOf(
            serializer<MenuRoute>().descriptor,
            serializer<CartRoute>().descriptor,
            serializer<CheckoutRoute>().descriptor,
        ).forEach { descriptor ->
            assertEquals("placeId", descriptor.getElementName(0))
        }

        // У меню вторым аргументом едет название заведения: в ответе
        // `food/places/{id}/menu` его нет, и шапка осталась бы пустой.
        val menu = serializer<MenuRoute>().descriptor
        assertEquals(2, menu.elementsCount)
        assertEquals("placeName", menu.getElementName(1))
        assertEquals(1, serializer<CartRoute>().descriptor.elementsCount)
        assertEquals(1, serializer<CheckoutRoute>().descriptor.elementsCount)

        val order = serializer<OrderStatusRoute>().descriptor
        assertEquals(1, order.elementsCount)
        assertEquals("orderId", order.getElementName(0))
    }

    @Test
    fun `food routes are distinguishable from each other`() {
        // Одинаковый serialName склеил бы меню, корзину и checkout в один
        // destination — при том, что аргумент у них один и тот же.
        val serialNames = listOf(
            serializer<MenuRoute>().descriptor.serialName,
            serializer<CartRoute>().descriptor.serialName,
            serializer<CheckoutRoute>().descriptor.serialName,
            serializer<OrderStatusRoute>().descriptor.serialName,
            serializer<PlaceRoute>().descriptor.serialName,
            // Очередь (issue #96): аргументы те же, что у меню, — склеенный
            // serialName увёл бы человека не на тот экран.
            serializer<QueueRoute>().descriptor.serialName,
            // Бронь (issue #97): аргументы те же, что у очереди и меню.
            serializer<BookingRoute>().descriptor.serialName,
            // Больницы (issue #99): и аргументы те же, и экран соседний —
            // склеенный serialName увёл бы с записи к врачу на запись к
            // мастеру.
            serializer<DoctorBookingRoute>().descriptor.serialName,
            // Анкеты (issue #84): одинаковый serialName склеил бы выбор роли
            // с обеими формами — аргумент у них один и тот же.
            serializer<RoleRoute>().descriptor.serialName,
            serializer<CustomerFormRoute>().descriptor.serialName,
            serializer<ProviderFormRoute>().descriptor.serialName,
        )

        assertEquals(serialNames.size, serialNames.toSet().size)
    }

    @Test
    fun `search route arguments are optional`() {
        // С главной сюда приходят и «просто поиск», и поиск с категорией —
        // оба аргумента должны быть необязательными.
        val empty = SearchRoute()

        assertEquals(empty, json.decodeFromString<SearchRoute>(json.encodeToString(empty)))
        assertEquals(
            listOf("categoryId", "query"),
            serializer<SearchRoute>().descriptor.let { descriptor ->
                (0 until descriptor.elementsCount).map(descriptor::getElementName)
            },
        )
    }

    @Test
    fun `graphs and argument-less routes are serializable`() {
        val serialNames = listOf(
            serializer<OnboardingGraph>().descriptor.serialName,
            serializer<MainGraph>().descriptor.serialName,
            serializer<BackendUrlRoute>().descriptor.serialName,
            serializer<UpdateRoute>().descriptor.serialName,
            serializer<WelcomeRoute>().descriptor.serialName,
            serializer<PhoneRoute>().descriptor.serialName,
            serializer<PinRoute>().descriptor.serialName,
            serializer<BiometricRoute>().descriptor.serialName,
            serializer<GeoRoute>().descriptor.serialName,
            serializer<DiscoveryRoute>().descriptor.serialName,
            serializer<OrdersRoute>().descriptor.serialName,
            serializer<WalletRoute>().descriptor.serialName,
            serializer<ProfileRoute>().descriptor.serialName,
            serializer<MapRoute>().descriptor.serialName,
            serializer<NotificationsRoute>().descriptor.serialName,
            // «Мои заведения» (issue #94): вне обоих графов, как уведомления.
            serializer<MyPlacesRoute>().descriptor.serialName,
        )
        // Маршруты обязаны быть различимы: одинаковые serialName склеили бы
        // разные destination'ы в один.
        assertEquals(serialNames.size, serialNames.toSet().size)
        serialNames.forEach { assertTrue(it.startsWith("uz.mahalla.navigation.")) }
    }

    @Test
    fun `place deep link placeholder matches the route argument`() {
        val descriptor = serializer<PlaceRoute>().descriptor
        assertEquals(1, descriptor.elementsCount)
        val argumentName = descriptor.getElementName(0)
        assertEquals("placeId", argumentName)
        assertTrue(DeepLinks.PLACE_PATTERN.endsWith("{$argumentName}"))
    }

    @Test
    fun `place deep link is built from the scheme`() {
        assertEquals("mahalla://place/p-42", DeepLinks.place("p-42"))
        assertTrue(DeepLinks.PLACE_PATTERN.startsWith("${DeepLinks.SCHEME}://"))
    }

    @Test
    fun `otp route carries the phone and challenge arguments`() {
        val descriptor = serializer<OtpRoute>().descriptor
        assertEquals(
            listOf("phone", "otpToken", "resendAfterSeconds", "codeLength", "channel"),
            (0 until descriptor.elementsCount).map(descriptor::getElementName),
        )
    }

    @Test
    fun `otp argument names match the route fields`() {
        // ViewModel читает аргументы из SavedStateHandle по строковым ключам:
        // расхождение с полями маршрута сломало бы экран только в рантайме.
        val descriptor = serializer<OtpRoute>().descriptor
        val fields = (0 until descriptor.elementsCount).map(descriptor::getElementName)
        assertEquals(
            listOf(
                OtpArgs.PHONE,
                OtpArgs.OTP_TOKEN,
                OtpArgs.RESEND_AFTER_SECONDS,
                OtpArgs.CODE_LENGTH,
                OtpArgs.CHANNEL,
            ),
            fields,
        )
    }

    @Test
    fun `queue route carries the place and its name`() {
        // Имя заведения едет маршрутом: в ответе `walkin/send` его нет, а
        // талон без имени места читается как чужой (issue #96).
        val descriptor = serializer<QueueRoute>().descriptor
        assertEquals(
            listOf("placeId", "placeName"),
            (0 until descriptor.elementsCount).map(descriptor::getElementName),
        )

        val route = QueueRoute(placeId = "p-1")
        assertEquals(route, json.decodeFromString<QueueRoute>(json.encodeToString(route)))
    }

    @Test
    fun `booking route carries the place and its name`() {
        // Имени заведения нет ни в ответе `barber-services`, ни в
        // `AppointmentResponse` — оно едет маршрутом (issue #97).
        val descriptor = serializer<BookingRoute>().descriptor
        assertEquals(
            listOf("placeId", "placeName"),
            (0 until descriptor.elementsCount).map(descriptor::getElementName),
        )

        val route = BookingRoute(placeId = "p-1")
        assertEquals(route, json.decodeFromString<BookingRoute>(json.encodeToString(route)))
    }

    @Test
    fun `doctor booking route carries the place and its name`() {
        // Имени больницы нет ни в ответе `hospitals/.../doctors`, ни в
        // `AppointmentResponse` — оно едет маршрутом (issue #99).
        val descriptor = serializer<DoctorBookingRoute>().descriptor
        assertEquals(
            listOf("placeId", "placeName"),
            (0 until descriptor.elementsCount).map(descriptor::getElementName),
        )

        val route = DoctorBookingRoute(placeId = "p-1")
        assertEquals(
            route,
            json.decodeFromString<DoctorBookingRoute>(json.encodeToString(route)),
        )
    }

    /**
     * Экран «мои записи» один на обе вертикали (issue #99), и различает их
     * единственный аргумент. Имя аргумента ViewModel читает из
     * `SavedStateHandle` по константе — разойдись они, и экран врача молча
     * показывал бы записи к мастеру.
     */
    @Test
    fun `my appointments route carries the vertical its view model reads`() {
        val descriptor = serializer<MyAppointmentsRoute>().descriptor
        assertEquals(1, descriptor.elementsCount)
        assertEquals(MyAppointmentsArgs.VERTICAL, descriptor.getElementName(0))

        // Умолчание — записи к мастеру: маршрут без аргумента открывают из
        // профиля и с экрана подтверждения брони.
        assertEquals(AppointmentVertical.Barber.name, MyAppointmentsRoute().vertical)

        val route = MyAppointmentsRoute(AppointmentVertical.Doctor.name)
        assertEquals(
            route,
            json.decodeFromString<MyAppointmentsRoute>(json.encodeToString(route)),
        )
    }
}
