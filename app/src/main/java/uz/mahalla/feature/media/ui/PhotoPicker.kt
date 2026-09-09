package uz.mahalla.feature.media.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Выбор снимка системным photo picker'ом (issue #101).
 *
 * Именно picker, а не `READ_MEDIA_IMAGES` с собственной галереей: он не
 * требует **никаких** разрешений, показывает только то, что человек сам
 * выбрал, и на устройствах без нового picker'а сам откатывается на
 * `ACTION_OPEN_DOCUMENT`. Просить доступ ко всей галерее ради одного аватара
 * — худшая сделка, которую можно предложить пользователю.
 *
 * Наружу отдаётся строка, а не `Uri`: с ней работает и ViewModel, которую
 * проверяют на чистом JVM (`Uri` там — заглушка, возвращающая `null`).
 *
 * Выданный доступ к адресу живёт, пока жив процесс. Приложение читает файл
 * сразу — но снимок, выбранный до смерти процесса, после восстановления уже
 * не откроется, и загрузка честно кончится «файл не читается».
 */
@Composable
fun rememberPhotoPicker(onPicked: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        // `null` — человек закрыл picker, ничего не выбрав. Это не отказ и не
        // ошибка: молча ничего не делаем.
        uri?.let { onPicked(it.toString()) }
    }
    return remember(launcher) {
        {
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
}
