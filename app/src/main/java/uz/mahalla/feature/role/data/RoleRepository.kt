package uz.mahalla.feature.role.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import uz.mahalla.core.crash.reportSwallowed
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.data.prefs.UserProfileStore
import uz.mahalla.feature.onboarding.domain.City
import uz.mahalla.feature.role.domain.CustomerForm
import uz.mahalla.feature.role.domain.ServerRole
import uz.mahalla.feature.role.domain.UserRole
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Кем человек пользуется приложением и что он рассказал о себе (issue #84).
 *
 * Всё лежит локально. Раньше это объяснялось тем, что профиля у бэкенда нет
 * вовсе; на самом деле `PUT /users/me` есть и принимает `fullName` — имя из
 * анкеты на сервер не уходит, и это отдельная задача (issue #234). Адрес
 * доставки серверу правда сообщить нечем: в `UpdateMeRequest` только
 * `fullName` и `avatarUrl` (`docs/API-CONTRACT.md`). Роль анкеты — тем более:
 * серверный `role` меняет только админ, и это про права, а не про анкету
 * (issue #237, [uz.mahalla.feature.role.domain.ServerRole]). Анкета продавца —
 * другое дело, она уходит в `POST /api/v1/places` через [ProviderRepository].
 *
 * Имя пишется в [UserProfileStore] (тот же профиль, что показывает шапка —
 * два разных имени у одного человека читались бы как ошибка), город — в
 * настройки (оттуда его берут координаты запросов), адрес — тоже в настройки,
 * рядом с городом.
 */
interface RoleRepository {

    val profile: Flow<RoleProfile>

    suspend fun current(): RoleProfile

    /**
     * Запомнить выбор роли, не дожидаясь заполнения анкеты: человек может
     * закрыть форму на полпути, и спрашивать «кто вы» второй раз незачем.
     */
    suspend fun selectRole(role: UserRole)

    /**
     * @return `false` — хранилище недоступно (нет места, права, IO). Экран
     * должен сказать об этом: анкета, которая «сохранилась» и пропала после
     * перезапуска, хуже честного отказа.
     */
    suspend fun saveCustomer(form: CustomerForm): Boolean
}

/**
 * Роль и анкета покупателя вместе: экран показывает их одним состоянием.
 *
 * @param role роль из анкеты — локальный выбор человека (issue #84).
 * @param serverRole права с сервера: их приложение не выбирает и не меняет
 * (issue #237). Лежат в [UserProfileStore] — их записал ответ на вход.
 * [ServerRole.Unknown] — входа ещё не было либо роль приложению незнакома.
 */
data class RoleProfile(
    val role: UserRole? = null,
    val serverRole: ServerRole = ServerRole.Unknown,
    val customer: CustomerForm = CustomerForm(),
) {

    /**
     * Оказывает ли человек услуги — по анкете **или** по правам на сервере
     * (issue #244). Правило общее с «Моими заведениями» в профиле, поэтому
     * живёт в домене:
     * [uz.mahalla.feature.role.domain.providesServices]. Одноимённое
     * свойство — не рекурсия: у `Boolean` нет `invoke`, и вызов уходит на
     * функцию домена.
     */
    val providesServices: Boolean
        get() = uz.mahalla.feature.role.domain.providesServices(role, serverRole)
}

@Singleton
class DataStoreRoleRepository @Inject constructor(
    private val settings: SettingsDataStore,
    private val profileStore: UserProfileStore,
) : RoleRepository {

    override val profile: Flow<RoleProfile> =
        combine(settings.settings, profileStore.profile) { appSettings, userProfile ->
            RoleProfile(
                role = UserRole.fromStoredValue(appSettings.roleId),
                serverRole = ServerRole.fromServer(userProfile.serverRole),
                customer = CustomerForm(
                    fullName = userProfile.fullName.orEmpty(),
                    city = City.fromId(appSettings.cityId),
                    address = appSettings.deliveryAddress.orEmpty(),
                ),
            )
        }.distinctUntilChanged()

    override suspend fun current(): RoleProfile = profile.first()

    override suspend fun selectRole(role: UserRole) {
        runCatchingCancellable { settings.setUserRole(role.storedValue) }
            .reportSwallowed("role.selectRole")
    }

    /**
     * Записи идут в два хранилища, и частичный успех возможен: имя сохранено,
     * адрес нет. Отдельного «отката» здесь не делаем — пользователь увидит в
     * форме то, что доехало, и допишет остальное. Важнее не соврать про успех.
     */
    override suspend fun saveCustomer(form: CustomerForm): Boolean {
        val trimmed = form.trimmed()
        return runCatchingCancellable {
            profileStore.save(profileStore.current().copy(fullName = trimmed.fullName))
            trimmed.city?.let { settings.setCityId(it.id) }
            settings.setDeliveryAddress(trimmed.address)
            settings.setUserRole(UserRole.Customer.storedValue)
        }.reportSwallowed("role.saveCustomer").isSuccess
    }
}
