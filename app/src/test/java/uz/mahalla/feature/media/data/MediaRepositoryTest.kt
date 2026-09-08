package uz.mahalla.feature.media.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.media.domain.MediaRejection
import uz.mahalla.feature.media.domain.MediaType
import uz.mahalla.testutil.FakeImageCompressor
import java.util.concurrent.TimeUnit

/**
 * Загрузка файла (issue #101) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]): это первый `multipart` в приложении, и проверять его
 * подменённым Retrofit'ом бессмысленно — ошибка была бы ровно в том, что
 * уходит в сокет.
 *
 * Сжатие подменено [FakeImageCompressor]: `BitmapFactory` на JVM нет, а
 * предмет проверки здесь — тело запроса, а не качество JPEG.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaRepositoryTest {

    private lateinit var server: MockWebServer
    private val compressor = FakeImageCompressor()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `file is posted as multipart with its name and type`() = runTest {
        server.enqueue(
            envelope(
                """{"id":"m-1","url":"https://cdn.mahalla.uz/m-1.jpg",
                   |"thumbnailUrl":"https://cdn.mahalla.uz/m-1-thumb.jpg",
                   |"type":"IMAGE","fileSize":19,"originalName":"photo.jpg"}"""
                    .trimMargin()
                    .replace("\n", ""),
            ),
        )

        val result = repository().uploadImage(SOURCE)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/media/upload", request.path)
        val contentType = request.getHeader("Content-Type").orEmpty()
        assertTrue(contentType, contentType.startsWith("multipart/form-data; boundary="))

        val body = request.body.readUtf8()
        // Имя части — из схемы бэкенда; имя файла и его тип объявляет клиент.
        assertTrue(body, """name="file"""" in body)
        assertTrue(body, """filename="photo.jpg"""" in body)
        assertTrue(body, "Content-Type: image/jpeg" in body)
        assertTrue(body, FakeImageCompressor.DEFAULT_BYTES in body)

        val media = (result as ApiResult.Success).data
        assertEquals("m-1", media.id)
        assertEquals("https://cdn.mahalla.uz/m-1.jpg", media.url)
        assertEquals("https://cdn.mahalla.uz/m-1-thumb.jpg", media.thumbnailUrl)
        assertEquals(MediaType.Image, media.type)
        assertEquals("photo.jpg", media.originalName)
        assertEquals(SOURCE, compressor.lastSource)
    }

    @Test
    fun `entity is named in query when the caller knows it`() = runTest {
        server.enqueue(envelope("""{"id":"m-2","url":"https://cdn.mahalla.uz/m-2.jpg"}"""))

        repository().uploadImage(SOURCE, entityType = "REVIEW", entityId = "r-1")

        assertEquals(
            "/media/upload?entityType=REVIEW&entityId=r-1",
            server.takeRequest().path,
        )
    }

    @Test
    fun `unknown entity is not invented`() = runTest {
        server.enqueue(envelope("""{"id":"m-3","url":"https://cdn.mahalla.uz/m-3.jpg"}"""))

        // Словаря `entityType` в схеме нет: не знаем — не отправляем. Retrofit
        // выбрасывает из query параметры со значением `null`.
        repository().uploadImage(SOURCE, entityType = null, entityId = null)

        assertEquals("/media/upload", server.takeRequest().path)
    }

    @Test
    fun `progress runs from zero to a hundred`() = runTest {
        server.enqueue(envelope("""{"id":"m-4","url":"https://cdn.mahalla.uz/m-4.jpg"}"""))
        compressor.bytes = ByteArray(40 * 1024) { 'a'.code.toByte() }
        val reported = mutableListOf<Int>()

        repository().uploadImage(SOURCE) { percent -> synchronized(reported) { reported += percent } }

        val progress = synchronized(reported) { reported.toList() }
        assertEquals(0, progress.first())
        assertEquals(100, progress.last())
        assertEquals(progress.sorted(), progress)
        // Одно и то же значение подряд не повторяется: иначе перерисовка на
        // каждый килобайт.
        assertEquals(progress.distinct(), progress)
    }

    @Test
    fun `rejected file never reaches the network`() = runTest {
        compressor.rejection = MediaRejection.TooLarge

        val result = repository().uploadImage(SOURCE)

        assertEquals(0, server.requestCount)
        assertEquals(
            ApiError.Business(MediaRejection.TooLarge.code),
            (result as ApiResult.Failure).error,
        )
    }

    @Test
    fun `response without url is not a success`() = runTest {
        // Файл без адреса показать и сохранить нечем: «успех», которым нечего
        // сделать, наружу уходит ошибкой разбора.
        server.enqueue(envelope("""{"id":"m-5","type":"IMAGE"}"""))

        val result = repository().uploadImage(SOURCE)

        assertEquals(ApiError.Serialization, (result as ApiResult.Failure).error)
    }

    @Test
    fun `envelope failure keeps the server text`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"MEDIA_QUOTA","message":"Joy tugadi"}}""",
                ),
        )

        val failure = (repository().uploadImage(SOURCE) as ApiResult.Failure).failure

        assertEquals(ApiError.Business("MEDIA_QUOTA"), failure.error)
        assertEquals("Joy tugadi", failure.serverMessage)
    }

    @Test
    fun `unauthorized upload is reported as such`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"UNAUTHORIZED",""" +
                        """"message":"Kirish uchun autentifikatsiya talab qilinadi"}}""",
                ),
        )

        val failure = (repository().uploadImage(SOURCE) as ApiResult.Failure).failure

        assertEquals(ApiError.Unauthorized, failure.error)
    }

    /**
     * Так отвечает стенд на тело больше 1 МиБ: `413` HTML'ом от nginx, без
     * конверта. Текста для человека в нём нет — тем и важно не доводить до
     * этого ответа (см. `MediaUploadLimits`).
     */
    @Test
    fun `nginx limit arrives as a plain http failure`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(413)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><head><title>413 Request Entity Too Large</title></head></html>"),
        )

        val failure = (repository().uploadImage(SOURCE) as ApiResult.Failure).failure

        assertEquals(413, (failure.error as ApiError.Http).code)
        assertNull("HTML сообщением для человека не становится", failure.serverMessage)
    }

    /**
     * Отмена — обычная отмена корутины: Retrofit обрывает вызов, и результата
     * не будет вовсе. Сервер молчит (`NO_RESPONSE`), то есть проверяется ровно
     * та ситуация, ради которой кнопка «Отменить» и нужна — долгая отправка.
     */
    @Test
    fun `upload is cancelled with its coroutine`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        var result: ApiResult<*>? = null

        val job = launch(Dispatchers.IO) { result = repository().uploadImage(SOURCE) }
        // Ждём, пока тело действительно уйдёт на сервер.
        assertNotNull(server.takeRequest(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertNull(result)
    }

    private fun repository() = DefaultMediaRepository(
        api = NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(MediaApi::class.java),
        compressor = compressor,
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")

    private companion object {
        const val SOURCE = "content://media/external/images/media/42"
        const val TIMEOUT_SECONDS = 5L
    }
}
