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
import uz.mahalla.feature.role.domain.UserRole
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Кем человек пользуется приложением и что он рассказал о себе (issue #84).
 *
 * Всё лежит локально, и это не упрощение: профиля пользователя у бэкенда нет
 * вовсе — ни `GET /users/me`, ни `PUT` (issue #61), — отправить имя и адрес
 * серверу нечем. Анкета продавца — другое дело, она уходит в
 * `POST /api/v1/places` через [ProviderRepository].
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

/** Роль и анкета покупателя вместе: экран показывает их одним состоянием. */
data class RoleProfile(
    val role: UserRole? = null,
    val customer: CustomerForm = CustomerForm(),
)

@Singleton
class DataStoreRoleRepository @Inject constructor(
    private val settings: SettingsDataStore,
    private val profileStore: UserProfileStore,
) : RoleRepository {

    override val profile: Flow<RoleProfile> =
        combine(settings.settings, profileStore.profile) { appSettings, userProfile ->
            RoleProfile(
                role = UserRole.fromStoredValue(appSettings.roleId),
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
