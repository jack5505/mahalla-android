package uz.mahalla.feature.role.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import uz.mahalla.core.crash.reportSwallowed
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.data.prefs.UserProfile
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
 * Город и адрес доставки лежат только локально: серверу их сообщить нечем — в
 * `UpdateMeRequest` только `fullName` и `avatarUrl` (`docs/API-CONTRACT.md`).
 * Роль анкеты — тем более локальная: серверный `role` меняет только админ, и
 * это про права, а не про анкету (issue #237,
 * [uz.mahalla.feature.role.domain.ServerRole]). Анкета продавца — другое дело,
 * она уходит в `POST /api/v1/places` через [ProviderRepository].
 *
 * Имя — исключение: оно пишется в [UserProfileStore] (тот же профиль, что
 * показывает шапка — два разных имени у одного человека читались бы как
 * ошибка) и рано или поздно должно дойти до `PUT users/me`, иначе на бэкенде
 * останется пустое имя (issue #234). Отправка не блокирует уход с анкеты —
 * первый же отказ сети не должен запирать человека на форме, — а помечается
 * [UserProfile.fullNamePendingSync]: следующий `ProfileRepository.refresh()`
 * (открытие вкладки «Профиль») сам повторит `PUT` вместо `GET`, пока сервер не
 * подтвердит имя.
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
 * @param serverRole права на сервере (issue #237, #244) — тот же источник,
 * что у [uz.mahalla.feature.profile.ui.ProfileState.serverRole]; читается из
 * [uz.mahalla.data.prefs.UserProfileStore], который [RoleRepository] уже
 * держит ради имени из анкеты.
 */
data class RoleProfile(
    val role: UserRole? = null,
    val customer: CustomerForm = CustomerForm(),
    val serverRole: ServerRole = ServerRole.Unknown,
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
                serverRole = ServerRole.fromServer(userProfile.serverRole),
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
     *
     * До сервера имя здесь не идёт: сеть в форме анкеты — риск запереть на ней
     * человека при первом же отказе (issue #234). Вместо этого выставляется
     * [UserProfile.fullNamePendingSync] — `ProfileRepository.refresh()` увидит
     * его при следующем открытии профиля и повторит `PUT users/me` сам.
     */
    override suspend fun saveCustomer(form: CustomerForm): Boolean {
        val trimmed = form.trimmed()
        return runCatchingCancellable {
            profileStore.save(
                profileStore.current().copy(
                    fullName = trimmed.fullName,
                    fullNamePendingSync = true,
                ),
            )
            trimmed.city?.let { settings.setCityId(it.id) }
            settings.setDeliveryAddress(trimmed.address)
            settings.setUserRole(UserRole.Customer.storedValue)
        }.reportSwallowed("role.saveCustomer").isSuccess
    }
}
