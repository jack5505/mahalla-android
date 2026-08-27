package uz.mahalla.testutil

import android.content.Intent
import okhttp3.Interceptor
import uz.mahalla.data.network.inspector.HttpInspector

/**
 * Инспектор трафика без Chucker'а (issue #30): настоящая библиотека тянет
 * Room-базу и экран, а проверять надо решения приложения — стоит ли
 * интерцептор в цепочке и показываем ли мы кнопку.
 *
 * `intent` по умолчанию `null`: [android.content.Intent] в чистом JVM-тесте не
 * создать, поэтому тесты, которым он нужен, идут под Robolectric и передают
 * его сами.
 */
class FakeHttpInspector(
    override val isAvailable: Boolean = true,
    override val interceptor: Interceptor? = null,
    private val intent: Intent? = null,
) : HttpInspector {

    override fun launchIntent(): Intent? = if (isAvailable) intent else null
}
