package uz.mahalla.core.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import uz.mahalla.R
import uz.mahalla.core.image.MahallaImageLoader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Картинка из сети (issue #60): скелетон → фото с проявлением → фоллбэк.
 *
 * Три состояния, и ни одно не пустое. Пока грузится — тот же мерцающий
 * прямоугольник, что и в списках ([SkeletonBox]): место под фото занято сразу,
 * поэтому список не прыгает, когда картинка доедет. Нет ссылки или загрузка
 * не удалась — иконка на приглушённом фоне: пустая дыра читается как «экран
 * сломался», а иконка — как «фото нет».
 *
 * Размер задаёт вызывающий: компонент занимает всё, что ему дали. Своей высоты
 * у него нет намеренно — в карточке это квадрат 64dp, в галерее полоса 160dp,
 * и «правильного» значения на всех не существует.
 *
 * @param contentDescription `null` — картинка декоративная (рядом уже есть
 * название), TalkBack её пропустит.
 */
@Composable
fun MahallaAsyncImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackIcon: ImageVector = Icons.Outlined.Photo,
    fallback: (@Composable BoxScope.() -> Unit)? = null,
) {
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(LocalContext.current)
            // Пустая строка — не ссылка: пустого запроса в сеть быть не должно.
            .data(url?.takeIf(String::isNotBlank))
            .crossfade(MahallaImageLoader.CROSSFADE_MS)
            .build(),
        contentScale = contentScale,
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(LocalMahallaColors.current.skeleton.copy(alpha = SURFACE_ALPHA))
            .clearAndSetSemantics {
                // Внутри Box'а несколько слоёв (фон, иконка, само фото) —
                // TalkBack должен услышать одну подпись или ничего.
                contentDescription?.let { this.contentDescription = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Loading -> SkeletonBox(
                modifier = Modifier.fillMaxSize(),
                height = null,
                shape = shape,
            )

            is AsyncImagePainter.State.Success -> Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )

            // Empty (ссылки нет) и Error (не загрузилось) для пользователя —
            // одно и то же: смотреть не на что.
            else -> if (fallback != null) fallback() else ImageFallbackIcon(fallbackIcon)
        }
    }
}

/**
 * Квадратная миниатюра для строки списка: карточка места, позиция меню.
 * Отдельный компонент, а не голый [MahallaAsyncImage] с размером — чтобы во
 * всех списках приложения фото было одинаковым.
 */
@Composable
fun MahallaThumbnail(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = MahallaComponentDefaults.thumbnailSize,
    fallbackIcon: ImageVector = Icons.Outlined.Photo,
) {
    MahallaAsyncImage(
        url = url,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        shape = MaterialTheme.shapes.small,
        fallbackIcon = fallbackIcon,
    )
}

/**
 * Аватар: круг, а вместо отсутствующего фото — первая буква имени. Буква
 * лучше силуэта: у списка отзывов она различает авторов, а силуэт делает их
 * одинаковыми.
 *
 * @param contentDescription по умолчанию — «аватар такого-то». `null` там, где
 * имя уже написано рядом (карточка отзыва): TalkBack не должен читать его
 * дважды.
 */
@Composable
fun MahallaAvatar(
    url: String?,
    name: String?,
    modifier: Modifier = Modifier,
    size: Dp = MahallaComponentDefaults.avatarSize,
    contentDescription: String? = name?.takeIf(String::isNotBlank)
        ?.let { stringResource(R.string.image_avatar_of, it) },
) {
    val initial = name?.trim()?.firstOrNull()?.uppercaseChar()?.toString()
    MahallaAsyncImage(
        url = url,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        shape = CircleShape,
        fallbackIcon = Icons.Outlined.Person,
        fallback = if (initial == null) {
            null
        } else {
            {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.titleMedium,
                    color = LocalMahallaColors.current.fgMuted,
                )
            }
        },
    )
}

@Composable
private fun BoxScope.ImageFallbackIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(MahallaComponentDefaults.cardIconSize),
        tint = LocalMahallaColors.current.fgMuted,
    )
}

/** Фон под фото: тот же цвет скелетона, но без мерцания. */
private const val SURFACE_ALPHA = 0.6f

@ThemeLanguagePreviews
@Composable
private fun MahallaImagesPreview() {
    PreviewSurface {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Превью рисуется без сети — видно ровно фоллбэки.
            MahallaThumbnail(url = null, contentDescription = null)
            MahallaAvatar(url = null, name = "Aziz")
            MahallaAvatar(url = null, name = null)
        }
    }
}
