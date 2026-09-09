# Правила ProGuard/R8 для release-сборки (эпик 13.4).
#
# Минификация включена (isMinifyEnabled = true) вместе с shrinkResources.
# Правило простое: всё, что резолвится не по прямой ссылке в коде — рефлексией,
# по имени класса, через JNI или через сгенерированный сериализатор, — R8 не
# видит и выкидывает или переименовывает. Такое падение случается только в
# release и только у пользователя, поэтому каждое правило ниже объяснено: без
# объяснения следующий человек не сможет понять, можно ли его убрать.
#
# Библиотеки, у которых правила приезжают своими consumer-rules и дублировать
# их не нужно: OkHttp, Retrofit (частично, см. ниже), Room, Hilt/Dagger,
# Sentry, DataStore, Compose, Navigation.

# --- Стектрейсы падений (issue #74) ---
# Без этих атрибутов отчёт в Sentry приходит без номеров строк, то есть
# бесполезен: видно класс, но не место. `renamesourcefileattribute` заодно
# прячет исходные имена файлов, оставляя разбор по mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- kotlinx.serialization ---
# Сериализатор лежит в синтетическом классе `Foo.$serializer`, а достаётся
# через `Foo.Companion.serializer()` — прямой ссылки на него в коде нет.
# Правила официальные (kotlinx.serialization README): без них разбор ответов
# бэкенда падает на `SerializationException: Serializer for class … not found`.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Сам класс `Foo.$serializer` держим целиком, а не только по правилам выше:
# release-сборку в CI проверить нечем (эмулятора нет), а цена ошибки —
# приложение, которое падает на первом же ответе бэкенда у всех. В dex это
# десятки килобайт против 6 МБ кода.
-keep class uz.mahalla.**$$serializer { *; }

# --- Retrofit ---
# Интерфейсы API реализуются в рантайме динамическим прокси, а типы ответов
# читаются из сигнатур методов. Consumer-rules Retrofit 2.11 покрывают
# библиотеку, но не наши интерфейсы: их методы должны пережить шринк вместе с
# generic-сигнатурами, иначе `Response<List<PlaceDto>>` вырождается в
# `Response` и конвертер не знает, во что разбирать тело.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepclasseswithmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# --- Yandex MapKit (эпик 4.2) ---
# SDK нативный: половина классов создаётся из C++ по имени через JNI, и никакой
# анализ достижимости этого не видит. Правило из документации MapKit — держать
# пакет целиком; урезать его можно только вместе с проверкой карты на
# устройстве, а эмулятора в CI нет.
-keep class com.yandex.** { *; }
-dontwarn com.yandex.**

# --- Coil (issue #60) ---
# Загрузчик собирает декодеры и мапперы рефлексией по классам, на которые в
# коде нет ни одной прямой ссылки: сам Coil от R8 не ломается, но его
# опциональные модули (GIF, SVG, video) резолвятся через Class.forName и без
# этих правил тихо выпадают из сборки.
-dontwarn coil.**
-keep class coil.util.** { *; }
# OkHttp/Okio Coil тянет тот же, что и приложение; их собственные правила
# приезжают из consumer-rules библиотек, дублировать не нужно.

# --- Предупреждения на отсутствующие классы ---
# Библиотеки ссылаются на опциональные API, которых нет в Android: без
# -dontwarn R8 в full mode останавливает сборку на «missing class».
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
