package uz.mahalla.feature.profile.ui

import android.app.Application
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.core.analytics.AnalyticsQueuedEvents
import uz.mahalla.core.analytics.AnalyticsScreens
import uz.mahalla.core.analytics.AnalyticsTracker
import uz.mahalla.core.locale.AppLanguage
import uz.mahalla.core.locale.AppLocaleManager
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.data.network.inspector.HttpInspector
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.data.prefs.UserProfile
import uz.mahalla.data.security.BiometricStatus
import uz.mahalla.feature.media.domain.MediaFile
import uz.mahalla.feature.media.domain.MediaRejection
import uz.mahalla.feature.notifications.push.NotificationChannels
import uz.mahalla.feature.profile.domain.DeviceSession
import uz.mahalla.testutil.FakeAnalyticsTracker
import uz.mahalla.testutil.FakeAuthRepository
import uz.mahalla.testutil.FakeBiometricAvailability
import uz.mahalla.testutil.FakeHttpInspector
import uz.mahalla.testutil.FakeMediaRepository
import uz.mahalla.testutil.FakePreferencesDataStore
import uz.mahalla.testutil.FakeProfileRepository
import uz.mahalla.testutil.FakeSessionsRepository
import uz.mahalla.testutil.FakeUserProfileStore
import uz.mahalla.testutil.MainDispatcherRule
import java.io.File

/**
 * Профиль: строка «сетевые запросы» (issue #30), шапка профиля, выход и
 * устройства (issue #61).
 *
 * Под Robolectric из-за DataStore и [Intent] — настройки в профиле настоящие.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProfileViewModelTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `opening the profile is tracked as a screen without a place`() = runTest {
        val analytics = FakeAnalyticsTracker()

        viewModel(analytics = analytics)

        assertEquals(
            listOf(AnalyticsQueuedEvents.screenOpened(AnalyticsScreens.PROFILE)),
            analytics.queuedEvents,
        )
    }

    @Test
    fun `inspector row opens the traffic screen`() = runTest {
        val intent = Intent("uz.mahalla.test.INSPECTOR")
        val viewModel = viewModel(inspector = FakeHttpInspector(intent = intent))

        assertTrue(viewModel.state.value.httpInspectorAvailable)
        viewModel.onEvent(ProfileEvent.HttpInspectorRequested)

        assertEquals(ProfileEffect.OpenHttpInspector(intent), viewModel.effects.first())
    }

    @Test
    fun `release build has no inspector row`() = runTest {
        val viewModel = viewModel(inspector = FakeHttpInspector(isAvailable = false))

        viewModel.onEvent(ProfileEvent.HttpInspectorRequested)
        // Событие из устаревшего состояния экрана не должно ни падать, ни
        // отдавать эффект: следующий в очереди — смена языка.
        viewModel.onEvent(ProfileEvent.LanguageSelected(AppLanguage.RUSSIAN))

        assertFalse(viewModel.state.value.httpInspectorAvailable)
        assertEquals(ProfileEffect.RecreateActivity, viewModel.effects.first())
    }

    @Test
    fun `header shows who is logged in`() = runTest {
        val profile = UserProfile(phone = "+998901234567", fullName = "Alisher Usmonov")

        val viewModel = viewModel(profileStore = FakeUserProfileStore(profile))

        assertEquals(profile, viewModel.state.value.profile)
    }

    // --- Профиль на сервере (issue #170) ---

    @Test
    fun `profile is read from the server when the screen opens`() = runTest {
        val store = FakeUserProfileStore(UserProfile(fullName = "Alisher Usmonov"))
        val profile = FakeProfileRepository(store)

        viewModel(profileStore = store, profile = profile)

        assertEquals(1, profile.refreshCount)
    }

    @Test
    fun `failed profile read keeps what the login already knows`() = runTest {
        val loginProfile = UserProfile(phone = "+998901234567", fullName = "Alisher Usmonov")
        val store = FakeUserProfileStore(loginProfile)
        val profile = FakeProfileRepository(store).apply {
            refreshResult = ApiResult.Failure(ApiError.NoConnection)
        }

        val viewModel = viewModel(profileStore = store, profile = profile)

        // Отказ `GET users/me` не опустошает шапку — на ней остаётся то, что
        // уже сохранил вход.
        assertEquals(loginProfile, viewModel.state.value.profile)
    }

    @Test
    fun `returning to the screen re-reads the profile`() = runTest {
        val store = FakeUserProfileStore(UserProfile(fullName = "Alisher Usmonov"))
        val profile = FakeProfileRepository(store)
        val viewModel = viewModel(profileStore = store, profile = profile)

        // Первый resume — это открытие экрана, `GET` уже запросил `init`.
        viewModel.onEvent(ProfileEvent.ScreenResumed)
        assertEquals(1, profile.refreshCount)

        viewModel.onEvent(ProfileEvent.ScreenResumed)

        // Профиль мог смениться на другом устройстве или в бизнес-панели,
        // пока приложение было в фоне.
        assertEquals(2, profile.refreshCount)
    }

    @Test
    fun `resume does not re-read the profile while a name save is in flight`() = runTest {
        val store = FakeUserProfileStore(UserProfile(fullName = "Alisher Usmonov"))
        val gate = CompletableDeferred<Unit>()
        val profile = FakeProfileRepository(store, gate = gate)
        val viewModel = viewModel(profileStore = store, profile = profile)

        viewModel.onEvent(ProfileEvent.ScreenResumed)
        assertEquals(1, profile.refreshCount)

        viewModel.onEvent(ProfileEvent.NameEditRequested)
        viewModel.onEvent(ProfileEvent.NameDraftChanged("Jahongir Sabirov"))
        viewModel.onEvent(ProfileEvent.NameSaveRequested)

        // `GET`, запущенный поверх ещё не пришедшего `PUT`, мог бы приехать
        // раньше и потом быть переписанным им — но мог и позже, стерев
        // только что сохранённое имя. Резюм в это время `GET` не зовёт вовсе.
        viewModel.onEvent(ProfileEvent.ScreenResumed)
        assertEquals(1, profile.refreshCount)

        gate.complete(Unit)
    }

    // --- Редактирование имени (issue #170) ---

    @Test
    fun `editing the name starts from what is already known`() = runTest {
        val store = FakeUserProfileStore(UserProfile(fullName = "Alisher Usmonov"))
        val viewModel = viewModel(profileStore = store)

        viewModel.onEvent(ProfileEvent.NameEditRequested)

        val nameEdit = viewModel.state.value.nameEdit
        assertTrue(nameEdit.editing)
        assertEquals("Alisher Usmonov", nameEdit.draft)
    }

    @Test
    fun `saved name goes to the server and closes the editor`() = runTest {
        val store = FakeUserProfileStore(
            UserProfile(id = "u-1", phone = "+998901234567", fullName = "Alisher Usmonov"),
        )
        val profile = FakeProfileRepository(store)
        val viewModel = viewModel(profileStore = store, profile = profile)

        viewModel.onEvent(ProfileEvent.NameEditRequested)
        viewModel.onEvent(ProfileEvent.NameDraftChanged("Jahongir Sabirov"))
        viewModel.onEvent(ProfileEvent.NameSaveRequested)

        assertEquals(listOf("Jahongir Sabirov" to null), profile.updates)
        assertFalse(viewModel.state.value.nameEdit.editing)
        assertEquals("Jahongir Sabirov", viewModel.state.value.profile.fullName)
        // Номер запись имени не трогает: `PUT` шлёт только изменённое поле.
        assertEquals("+998901234567", viewModel.state.value.profile.phone)
    }

    @Test
    fun `refused name save shows the server reason and keeps the draft`() = runTest {
        val store = FakeUserProfileStore(UserProfile(fullName = "Alisher Usmonov"))
        val profile = FakeProfileRepository(store).apply {
            updateResult = ApiResult.Failure(ApiError.Business("VALIDATION_ERROR"))
        }
        val viewModel = viewModel(profileStore = store, profile = profile)

        viewModel.onEvent(ProfileEvent.NameEditRequested)
        viewModel.onEvent(ProfileEvent.NameDraftChanged("Jahongir Sabirov"))
        viewModel.onEvent(ProfileEvent.NameSaveRequested)

        val nameEdit = viewModel.state.value.nameEdit
        assertTrue(nameEdit.editing)
        assertFalse(nameEdit.saving)
        assertEquals("Jahongir Sabirov", nameEdit.draft)
        assertEquals(ApiError.Business("VALIDATION_ERROR"), nameEdit.failure?.error)
        // Имя на сервере не менялось — старое и осталось.
        assertEquals("Alisher Usmonov", store.current().fullName)
    }

    @Test
    fun `blank name is not sent`() = runTest {
        val store = FakeUserProfileStore(UserProfile(fullName = "Alisher Usmonov"))
        val profile = FakeProfileRepository(store)
        val viewModel = viewModel(profileStore = store, profile = profile)

        viewModel.onEvent(ProfileEvent.NameEditRequested)
        viewModel.onEvent(ProfileEvent.NameDraftChanged("   "))
        viewModel.onEvent(ProfileEvent.NameSaveRequested)

        assertTrue(profile.updates.isEmpty())
        assertTrue(viewModel.state.value.nameEdit.editing)
    }

    @Test
    fun `name longer than the server limit is not sent`() = runTest {
        val store = FakeUserProfileStore(UserProfile(fullName = "Alisher Usmonov"))
        val profile = FakeProfileRepository(store)
        val viewModel = viewModel(profileStore = store, profile = profile)

        viewModel.onEvent(ProfileEvent.NameEditRequested)
        viewModel.onEvent(ProfileEvent.NameDraftChanged("x".repeat(NameEdit.MAX_LENGTH + 1)))
        viewModel.onEvent(ProfileEvent.NameSaveRequested)

        // Сервер отклонил бы это `VALIDATION_ERROR`: показываем то же самое,
        // не тратя запрос.
        assertTrue(profile.updates.isEmpty())
        assertTrue(viewModel.state.value.nameEdit.editing)
    }

    @Test
    fun `avatar upload waits for an in-flight name save`() = runTest {
        val store = FakeUserProfileStore(UserProfile(fullName = "Alisher Usmonov"))
        val gate = CompletableDeferred<Unit>()
        val profile = FakeProfileRepository(store, gate = gate)
        val media = FakeMediaRepository(
            result = ApiResult.Success(MediaFile(id = "m-9", url = "https://cdn.mahalla.uz/a.jpg")),
        )
        val viewModel = viewModel(profileStore = store, profile = profile, media = media)

        viewModel.onEvent(ProfileEvent.NameEditRequested)
        viewModel.onEvent(ProfileEvent.NameDraftChanged("Jahongir Sabirov"))
        viewModel.onEvent(ProfileEvent.NameSaveRequested)
        // Имя ещё в полёте (ждёт gate) — второй `PUT` не должен стартовать и
        // получить ответ раньше первого.
        viewModel.onEvent(ProfileEvent.AvatarPicked(SOURCE))

        assertTrue(media.uploads.isEmpty())
        assertEquals(listOf("Jahongir Sabirov" to null), profile.updates)

        gate.complete(Unit)
    }

    @Test
    fun `name save waits for an in-flight avatar upload`() = runTest {
        val store = FakeUserProfileStore(UserProfile(fullName = "Alisher Usmonov"))
        val profile = FakeProfileRepository(store)
        val gate = CompletableDeferred<Unit>()
        val media = FakeMediaRepository(
            result = ApiResult.Success(MediaFile(id = "m-9", url = "https://cdn.mahalla.uz/a.jpg")),
            gate = gate,
        )
        val viewModel = viewModel(profileStore = store, profile = profile, media = media)

        viewModel.onEvent(ProfileEvent.AvatarPicked(SOURCE))
        viewModel.onEvent(ProfileEvent.NameEditRequested)
        viewModel.onEvent(ProfileEvent.NameDraftChanged("Jahongir Sabirov"))
        viewModel.onEvent(ProfileEvent.NameSaveRequested)

        // Аватар ещё грузится — имя не отправлено: оба `PUT` переписывают
        // профиль целиком, и ответ, приехавший не в том порядке, стёр бы
        // только что сохранённое поле.
        assertTrue(profile.updates.isEmpty())

        gate.complete(Unit)
    }

    @Test
    fun `cancelling the edit does not touch the server`() = runTest {
        val store = FakeUserProfileStore(UserProfile(fullName = "Alisher Usmonov"))
        val profile = FakeProfileRepository(store)
        val viewModel = viewModel(profileStore = store, profile = profile)

        viewModel.onEvent(ProfileEvent.NameEditRequested)
        viewModel.onEvent(ProfileEvent.NameDraftChanged("Jahongir Sabirov"))
        viewModel.onEvent(ProfileEvent.NameEditCancelled)

        assertFalse(viewModel.state.value.nameEdit.editing)
        assertTrue(profile.updates.isEmpty())
        assertEquals("Alisher Usmonov", store.current().fullName)
    }

    @Test
    fun `logout asks first and only then wipes the session`() = runTest {
        val auth = FakeAuthRepository(initialAuthorized = true)
        val settings = SettingsDataStore(newDataStore())
        settings.setOnboardingCompleted(true)
        val viewModel = viewModel(auth = auth, settings = settings)

        viewModel.onEvent(ProfileEvent.LogoutRequested)
        assertTrue(viewModel.state.value.confirmLogout)
        // Пока не подтвердили — сессия на месте: «Выйти» рядом с языком и
        // темой слишком легко нажать случайно.
        assertEquals(0, auth.logoutCount)

        viewModel.onEvent(ProfileEvent.LogoutConfirmed)

        assertEquals(1, auth.logoutCount)
        assertFalse(viewModel.state.value.confirmLogout)
        assertEquals(ProfileEffect.LoggedOut, viewModel.effects.first())
        // Кнопка не остаётся крутиться: экран уходит, но состояние честное.
        assertFalse(viewModel.state.value.loggingOut)
        // Иначе следующий запуск привёл бы в main без сессии, где каждый
        // запрос отвечает 401.
        assertFalse(settings.current().onboardingCompleted)
    }

    @Test
    fun `dismissed logout keeps the session`() = runTest {
        val auth = FakeAuthRepository(initialAuthorized = true)
        val viewModel = viewModel(auth = auth)

        viewModel.onEvent(ProfileEvent.LogoutRequested)
        viewModel.onEvent(ProfileEvent.LogoutDismissed)

        assertFalse(viewModel.state.value.confirmLogout)
        assertEquals(0, auth.logoutCount)
    }

    @Test
    fun `devices are loaded on open`() = runTest {
        val sessions = FakeSessionsRepository(listOf(session("s-1"), session("s-2")))

        val viewModel = viewModel(sessions = sessions)

        val state = viewModel.state.value.sessions
        assertEquals(listOf("s-1", "s-2"), (state as ScreenState.Content).data.map { it.id })
    }

    @Test
    fun `failed devices request keeps the reason`() = runTest {
        val sessions = FakeSessionsRepository().apply {
            sessionsResult = ApiResult.Failure(ApiError.NoConnection)
        }

        val viewModel = viewModel(sessions = sessions)

        val state = viewModel.state.value.sessions
        assertEquals(ApiError.NoConnection, (state as ScreenState.Error).error)

        viewModel.onEvent(ProfileEvent.SessionsRetryRequested)
        assertEquals(2, sessions.sessionsCount)
    }

    @Test
    fun `revoking a device asks first and reloads the list`() = runTest {
        val other = session("s-2")
        val sessions = FakeSessionsRepository(listOf(session("s-1", current = true), other))
        val viewModel = viewModel(sessions = sessions)

        viewModel.onEvent(ProfileEvent.SessionRevokeRequested(other))
        assertEquals(other, viewModel.state.value.confirmRevoke)
        assertTrue(sessions.revoked.isEmpty())

        viewModel.onEvent(ProfileEvent.SessionRevokeConfirmed)

        assertEquals(listOf("s-2"), sessions.revoked)
        assertNull(viewModel.state.value.confirmRevoke)
        assertNull(viewModel.state.value.pendingSessionId)
        // Список перечитывается у сервера: сессия могла быть не одна.
        assertEquals(2, sessions.sessionsCount)
    }

    @Test
    fun `this device is not revoked from the list`() = runTest {
        val current = session("s-1", current = true)
        val sessions = FakeSessionsRepository(listOf(current))
        val viewModel = viewModel(sessions = sessions)

        viewModel.onEvent(ProfileEvent.SessionRevokeRequested(current))
        viewModel.onEvent(ProfileEvent.SessionRevokeConfirmed)

        // Отзыв своей сессии — это выход, о котором экран не сказал ни слова.
        assertNull(viewModel.state.value.confirmRevoke)
        assertTrue(sessions.revoked.isEmpty())
    }

    @Test
    fun `refused revoke shows the server reason and keeps the device`() = runTest {
        val other = session("s-2")
        val sessions = FakeSessionsRepository(listOf(other)).apply {
            revokeResult = ApiResult.Failure(ApiError.Business("SESSION_NOT_FOUND"))
        }
        val viewModel = viewModel(sessions = sessions)

        viewModel.onEvent(ProfileEvent.SessionRevokeRequested(other))
        viewModel.onEvent(ProfileEvent.SessionRevokeConfirmed)

        val state = viewModel.state.value
        assertEquals(ApiError.Business("SESSION_NOT_FOUND"), state.sessionFailure?.error)
        assertNull(state.pendingSessionId)
        // Список не перечитывался: отзыва не было.
        assertEquals(1, sessions.sessionsCount)
    }

    @Test
    fun `trust toggle goes to the server and reloads`() = runTest {
        val other = session("s-2")
        val sessions = FakeSessionsRepository(listOf(other))
        val viewModel = viewModel(sessions = sessions)

        viewModel.onEvent(ProfileEvent.SessionTrustToggled(other, trusted = true))

        assertEquals(listOf("s-2" to true), sessions.trustChanges)
        assertEquals(2, sessions.sessionsCount)
    }

    @Test
    fun `returning to the screen refreshes the devices`() = runTest {
        val sessions = FakeSessionsRepository(listOf(session("s-1")))
        val viewModel = viewModel(sessions = sessions)

        // Первый resume — это открытие экрана, список уже запросил `init`.
        viewModel.onEvent(ProfileEvent.ScreenResumed)
        assertEquals(1, sessions.sessionsCount)

        viewModel.onEvent(ProfileEvent.ScreenResumed)

        // Вход с другого устройства мог случиться, пока приложение было в фоне.
        assertEquals(2, sessions.sessionsCount)
        // Скелетон при этом не показываем — список бы мигал на каждом возврате.
        assertTrue(viewModel.state.value.sessions is ScreenState.Content)
    }

    private fun session(id: String, current: Boolean = false) = DeviceSession(
        id = id,
        deviceName = "Device $id",
        isCurrent = current,
    )

    // --- Фото профиля (issue #101) ---

    @Test
    fun `uploaded photo is sent to the server via PUT users-me`() = runTest {
        val store = FakeUserProfileStore(
            UserProfile(id = "u-1", phone = "+998901234567", fullName = "Alisher Usmonov"),
        )
        val profile = FakeProfileRepository(store)
        val media = FakeMediaRepository(
            result = ApiResult.Success(MediaFile(id = "m-9", url = "https://cdn.mahalla.uz/a.jpg")),
        )
        val viewModel = viewModel(profileStore = store, media = media, profile = profile)

        viewModel.onEvent(ProfileEvent.AvatarPicked(SOURCE))

        // `PUT` — не запись в обход сервера: адрес уходит туда же, откуда его
        // потом читает `GET`.
        assertEquals(listOf(null to "https://cdn.mahalla.uz/a.jpg"), profile.updates)
        assertEquals("https://cdn.mahalla.uz/a.jpg", store.current().avatarUrl)
        // Имя и номер запись аватара не трогает: `PUT` шлёт только `avatarUrl`.
        assertEquals("Alisher Usmonov", store.current().fullName)
        assertEquals("+998901234567", store.current().phone)
        assertFalse(viewModel.state.value.avatarUpload.inProgress)
        assertNull(viewModel.state.value.avatarUpload.failure)
        // `entityId` — id пользователя: по нему загруженное потом находится.
        assertEquals("u-1", media.uploads.single().entityId)
        // `entityType` не выдумываем: словаря значений в схеме нет.
        assertNull(media.uploads.single().entityType)
        assertEquals(SOURCE, media.uploads.single().source)
    }

    @Test
    fun `progress is shown while the file goes up`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val media = FakeMediaRepository(progress = listOf(0, 30, 70), gate = gate)
        val viewModel = viewModel(media = media)

        viewModel.onEvent(ProfileEvent.AvatarPicked(SOURCE))

        assertTrue(viewModel.state.value.avatarUpload.inProgress)
        assertEquals(70, viewModel.state.value.avatarUpload.percent)

        gate.complete(Unit)
        assertFalse(viewModel.state.value.avatarUpload.inProgress)
    }

    @Test
    fun `second pick does not start a second upload`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val media = FakeMediaRepository(gate = gate)
        val viewModel = viewModel(media = media)

        viewModel.onEvent(ProfileEvent.AvatarPicked(SOURCE))
        viewModel.onEvent(ProfileEvent.AvatarPicked(SOURCE))

        assertEquals(1, media.uploads.size)
        gate.complete(Unit)
    }

    @Test
    fun `cancelled upload leaves neither progress nor photo`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = FakeUserProfileStore(UserProfile(id = "u-1"))
        val viewModel = viewModel(profileStore = store, media = FakeMediaRepository(gate = gate))

        viewModel.onEvent(ProfileEvent.AvatarPicked(SOURCE))
        viewModel.onEvent(ProfileEvent.AvatarUploadCancelled)

        assertFalse(viewModel.state.value.avatarUpload.inProgress)
        assertEquals(0, viewModel.state.value.avatarUpload.percent)
        // Отменённая загрузка ничего не сохраняет, даже если ответ приедет.
        gate.complete(Unit)
        assertNull(store.current().avatarUrl)
    }

    @Test
    fun `failed upload explains itself and keeps the old photo`() = runTest {
        val store = FakeUserProfileStore(UserProfile(avatarUrl = "https://cdn.mahalla.uz/old.jpg"))
        val media = FakeMediaRepository(
            result = ApiResult.Failure(ApiError.Business(MediaRejection.TooLarge.code)),
        )
        val viewModel = viewModel(profileStore = store, media = media)

        viewModel.onEvent(ProfileEvent.AvatarPicked(SOURCE))

        val upload = viewModel.state.value.avatarUpload
        assertFalse(upload.inProgress)
        assertEquals(
            ApiError.Business(MediaRejection.TooLarge.code),
            requireNotNull(upload.failure).error,
        )
        assertEquals("https://cdn.mahalla.uz/old.jpg", store.current().avatarUrl)
    }

    @Test
    fun `refused PUT after a successful upload explains itself`() = runTest {
        val store = FakeUserProfileStore(UserProfile(avatarUrl = "https://cdn.mahalla.uz/old.jpg"))
        val profile = FakeProfileRepository(store).apply {
            updateResult = ApiResult.Failure(ApiError.Business("VALIDATION_ERROR"))
        }
        val media = FakeMediaRepository(
            result = ApiResult.Success(MediaFile(id = "m-9", url = "https://cdn.mahalla.uz/a.jpg")),
        )
        val viewModel = viewModel(profileStore = store, media = media, profile = profile)

        viewModel.onEvent(ProfileEvent.AvatarPicked(SOURCE))

        // Файл уже лежит на сервере (`ownerId` — загрузивший), но профиль его
        // ещё не знает: отказ `PUT` показываем, а старый адрес не стираем.
        val upload = viewModel.state.value.avatarUpload
        assertFalse(upload.inProgress)
        assertEquals(ApiError.Business("VALIDATION_ERROR"), requireNotNull(upload.failure).error)
        assertEquals("https://cdn.mahalla.uz/old.jpg", store.current().avatarUrl)
    }

    // --- Тумблер «Вход по отпечатку» (макет 2d) ---
    // DataStore здесь в памяти: у пути записи нет эффекта, за которым можно
    // подождать, а файловый DataStore на IO не всегда успевал за таймаут.

    @Test
    fun `enabling biometrics asks for the system prompt before writing the flag`() = runTest {
        val settings = SettingsDataStore(FakePreferencesDataStore())
        val viewModel = viewModel(settings = settings, biometrics = FakeBiometricAvailability(BiometricStatus.Available))

        viewModel.onEvent(ProfileEvent.BiometricToggled(enabled = true))

        assertEquals(ProfileEffect.ShowBiometricPrompt, viewModel.effects.first())
        // Флаг пишется только после подтверждения: закрытый диалог не должен
        // оставить «биометрия включена».
        assertFalse(settings.current().biometricEnabled)
    }

    @Test
    fun `a confirmed prompt turns biometrics on`() = runTest {
        val settings = SettingsDataStore(FakePreferencesDataStore())
        val viewModel = viewModel(settings = settings)

        viewModel.onEvent(ProfileEvent.BiometricToggled(enabled = true))
        viewModel.onEvent(ProfileEvent.BiometricPromptSucceeded)

        assertTrue(settings.current().biometricEnabled)
    }

    @Test
    fun `disabling biometrics writes the flag without a prompt`() = runTest {
        val settings = SettingsDataStore(FakePreferencesDataStore())
        settings.setBiometricEnabled(true)
        val viewModel = viewModel(settings = settings)

        viewModel.onEvent(ProfileEvent.BiometricToggled(enabled = false))

        assertFalse(settings.current().biometricEnabled)
    }

    @Test
    fun `a failed prompt is explained and cleared by the next attempt`() = runTest {
        val settings = SettingsDataStore(FakePreferencesDataStore())
        val viewModel = viewModel(settings = settings)

        viewModel.onEvent(ProfileEvent.BiometricToggled(enabled = true))
        viewModel.onEvent(ProfileEvent.BiometricPromptFailed)
        assertTrue(viewModel.state.value.biometricPromptFailed)
        assertFalse(settings.current().biometricEnabled)

        viewModel.onEvent(ProfileEvent.BiometricToggled(enabled = true))

        assertFalse(viewModel.state.value.biometricPromptFailed)
    }

    /**
     * Без датчика строки нет вовсе; без отпечатков она есть, но выключена — и
     * событие включения, если доехало, флаг не пишет.
     */
    @Test
    fun `without a sensor there is no row and without enrolment nothing is written`() = runTest {
        val settings = SettingsDataStore(FakePreferencesDataStore())
        val noSensor = viewModel(settings = settings, biometrics = FakeBiometricAvailability(BiometricStatus.NoHardware))
        assertFalse(noSensor.state.value.showsBiometricRow)

        val notEnrolled = viewModel(settings = settings, biometrics = FakeBiometricAvailability(BiometricStatus.NotEnrolled))
        assertTrue(notEnrolled.state.value.showsBiometricRow)

        notEnrolled.onEvent(ProfileEvent.BiometricToggled(enabled = true))

        assertFalse(settings.current().biometricEnabled)
    }

    @Test
    fun `returning to the screen re-reads biometric availability`() = runTest {
        // Отпечаток добавили в настройках устройства и вернулись в профиль.
        val biometrics = FakeBiometricAvailability(BiometricStatus.NotEnrolled)
        val viewModel = viewModel(biometrics = biometrics)
        assertEquals(BiometricStatus.NotEnrolled, viewModel.state.value.biometricStatus)

        biometrics.status = BiometricStatus.Available
        viewModel.onEvent(ProfileEvent.ScreenResumed)

        assertEquals(BiometricStatus.Available, viewModel.state.value.biometricStatus)
    }

    private fun viewModel(
        inspector: HttpInspector = FakeHttpInspector(),
        profileStore: FakeUserProfileStore = FakeUserProfileStore(),
        sessions: FakeSessionsRepository = FakeSessionsRepository(),
        auth: FakeAuthRepository = FakeAuthRepository(),
        settings: SettingsDataStore = SettingsDataStore(newDataStore()),
        media: FakeMediaRepository = FakeMediaRepository(),
        profile: FakeProfileRepository = FakeProfileRepository(profileStore),
        biometrics: FakeBiometricAvailability = FakeBiometricAvailability(),
        analytics: AnalyticsTracker = FakeAnalyticsTracker(),
    ) = ProfileViewModel(
        settingsDataStore = settings,
        localeManager = RecreatingLocaleManager,
        httpInspector = inspector,
        userProfileStore = profileStore,
        sessionsRepository = sessions,
        authRepository = auth,
        mediaRepository = media,
        profileRepository = profile,
        biometricAvailability = biometrics,
        notificationChannels = NotificationChannels(
            context = ApplicationProvider.getApplicationContext(),
            settings = settings,
        ),
        analytics = analytics,
    )

    /** На один файл в процессе допустим ровно один экземпляр DataStore. */
    private fun newDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.root, "profile-vm.preferences_pb") },
    )

    private companion object {
        const val SOURCE = "content://media/external/images/media/42"
    }

    /** API < 33: смену языка применяет пересоздание Activity. */
    private object RecreatingLocaleManager : AppLocaleManager {
        override fun apply(language: AppLanguage): Boolean = true
        override fun systemApplied(): AppLanguage? = null
    }
}
