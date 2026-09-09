#!/usr/bin/env bash
#
# Пилот контрактных проб: вертикаль «Бронирование» (BookingApi ⚠️).
#
# Почему именно она первой: у неё замкнутый цикл создать → список → отменить,
# то есть проба сама за собой убирает и не оставляет мусор на общем стенде.
# И главное — в BookingApi.kt имена полей `BookAppointmentRequest` выведены,
# а не прочитаны: схема `BookRequest` перекрыта коллизией springdoc, а живым
# запросом без токена форму тела не проверить (401 приходит до валидации).
# Проба под токеном — единственный способ узнать, угадали ли.
#
#   contract/booking.sh
#
# Переменные — см. contract/lib.sh. Дополнительно:
#   CONTRACT_BOOKING_PLACE_ID  uuid заведения с услугами; не задан — ищется
#                              перебором по places/nearby

set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=contract/lib.sh
source contract/lib.sh

SAMPLES=app/src/test/resources/contract/booking
contract_init "$SAMPLES"
contract_login

tomorrow() {
    date -u -d '+1 day' +%Y-%m-%d 2>/dev/null || date -u -v+1d +%Y-%m-%d
}

# Каталог стенда бывает пуст (issue #53), а услуги есть не у всякого места,
# поэтому заведение подбираем перебором, а не берём первое попавшееся.
find_place_with_services() {
    if [ -n "${CONTRACT_BOOKING_PLACE_ID:-}" ]; then
        printf '%s' "$CONTRACT_BOOKING_PLACE_ID"
        return 0
    fi
    # lat/lng/radiusMeters — обязательные query-параметры (CatalogApi.kt),
    # гео-заголовки их не заменяют: они про доступ, а не про точку поиска.
    # Пагинации у выдачи нет — бэкенд отдаёт всё одним списком.
    local nearby
    nearby=$(_curl "$CONTRACT_BASE_URL/places/nearby?lat=$CONTRACT_GEO_LAT&lng=$CONTRACT_GEO_LNG&radiusMeters=${CONTRACT_RADIUS_METERS:-10000}") || return 1
    local ids
    ids=$(printf '%s' "$nearby" | jq -r '(.data // [])[] | select(type == "object") | .id // empty' 2>/dev/null | head -20)
    local id
    for id in $ids; do
        local services
        services=$(_curl "$CONTRACT_BASE_URL/barber-services/places/$id") || continue
        if [ "$(printf '%s' "$services" | jq -r '(.data // []) | length' 2>/dev/null)" != "0" ]; then
            printf '%s' "$id"
            return 0
        fi
    done
    return 1
}

echo "── стенд: $CONTRACT_BASE_URL"

PLACE_ID=$(find_place_with_services || true)
if [ -z "$PLACE_ID" ]; then
    echo "✗ не нашёл заведения с услугами — каталог стенда пуст?"
    echo "  задай CONTRACT_BOOKING_PLACE_ID вручную"
    contract_summary
    exit 1
fi
echo "── заведение: $PLACE_ID"

# 1-2. Анонимная часть: услуги и слоты отвечают без токена (проверено #97).
probe services 200 GET "barber-services/places/$PLACE_ID" || true

SERVICE_ID=$(jq -r '(.data // [])[0].id // empty' "$SAMPLES/services.json" 2>/dev/null || true)
DATE=$(tomorrow)
if [ -n "$SERVICE_ID" ]; then
    probe slots 200 GET "barber-services/places/$PLACE_ID/slots?serviceId=$SERVICE_ID&date=$DATE" || true
else
    skip slots "у заведения нет id услуги"
fi

# 3. Ради чего всё затевалось: подтвердить имена полей тела запроса.
#    Ответ 400 VALIDATION_ERROR здесь = имена не угадали, чинить BookingApi.kt.
SLOT=$(jq -r '(.data // [])[0] // empty' "$SAMPLES/slots.json" 2>/dev/null || true)
if [ -n "$ACCESS_TOKEN" ] && [ -n "$SERVICE_ID" ] && [ -n "$SLOT" ]; then
    [ "${#SLOT}" = "5" ] && SLOT="$SLOT:00"
    BOOK_BODY=$(jq -n --arg p "$PLACE_ID" --arg s "$SERVICE_ID" \
        --arg d "$DATE" --arg t "$SLOT" \
        '{placeId: $p, serviceId: $s, date: $d, startTime: $t}')
    # 201 наравне с 200: на создание Spring отвечает то так, то так, и это
    # не расхождение контракта — клиенту важно тело, а не код.
    probe_auth book 200,201 POST appointments "$BOOK_BODY" || true
else
    skip book "нет токена, услуги или свободного слота"
fi

# 4. Свои записи.
probe_auth my 200 GET "appointments/my?page=0&size=20" || true

# 5. Прибираем за собой: отменяем ровно то, что создали выше.
APPT_ID=$(jq -r '.data.id // empty' "$SAMPLES/book.json" 2>/dev/null || true)
if [ -n "$APPT_ID" ]; then
    probe_auth cancel 200 POST "appointments/$APPT_ID/cancel" || true
else
    skip cancel "запись не создавалась, отменять нечего"
fi

contract_summary
