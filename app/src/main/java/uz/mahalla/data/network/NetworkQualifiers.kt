package uz.mahalla.data.network

import javax.inject.Qualifier

/** `baseUrl`, выбранный buildType'ом (эпик 1.3). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BaseUrl

/**
 * Клиент без `AuthInterceptor`/`TokenAuthenticator` — им ходит только сам
 * refresh. Иначе refresh, получив 401, звал бы себя рекурсивно.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RefreshClient

/**
 * Разрешает ли сборка менять адрес бэкенда (issue #26) —
 * `BuildConfig.BACKEND_URL_OVERRIDE`. В обычном релизе выключено: экран ввода
 * адреса не показывается и сохранённый адрес не применяется.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackendUrlOverride
