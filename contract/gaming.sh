#!/usr/bin/env bash
#
# Контрактные пробы: вертикаль «Игровые зоны» (GamingApi ⚠️ частично, issue #425).
#
# Схема только что поменялась (issue #406: `totalSeats` → `totalUnits`,
# `zoneType` — закрытый справочник, новая ручка `zones/{zoneId}/units`) и
# следующий бэкенд-шаг V42 (`freeUnitsNow`, бронь по `unitId`, issue #409)
# поменяет её снова — подтверждение в docs/API-CONTRACT.md было разовыми
# curl'ами от 2026-10-02, переписанными в документ руками.
#
# Брони проба не создаёт: отмены у бэкенда нет вовсе (ни в `gaming-controller`,
# ни в общем `orders` — там для GAMING только GET), а проба, которая не может
# убрать за собой, оставляет мусор на общем стенде (правило `booking.sh`).
# Поэтому `POST gaming/bookings` по-прежнему не снят — тело сверено только
# чтением схемы (GamingApi.kt).
#
#   contract/gaming.sh
#
# Переменные — см. contract/lib.sh. Дополнительно:
#   CONTRACT_GAMING_PLACE_ID  uuid заведения с игровыми зонами; не задан —
#                             ищется перебором по places/nearby?category=GAMING

set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=contract/lib.sh
source contract/lib.sh

SAMPLES=app/src/test/resources/contract/gaming
contract_init "$SAMPLES"
contract_login

# Каталог стенда бывает пуст и заведение категории GAMING может не завести ни
# одной зоны, поэтому заведение подбираем перебором, а не берём первое
# попавшееся (та же грабля issue #53, что и у booking.sh).
find_place_with_zones() {
    if [ -n "${CONTRACT_GAMING_PLACE_ID:-}" ]; then
        printf '%s' "$CONTRACT_GAMING_PLACE_ID"
        return 0
    fi
    # lat/lng/radiusMeters — обязательные query-параметры (CatalogApi.kt),
    # гео-заголовки их не заменяют: они про доступ, а не про точку поиска.
    # category=GAMING (PlaceCategory.Playground) сужает перебор до заведений
    # нужной категории — без него пришлось бы гадать по всему каталогу.
    local nearby
    nearby=$(_curl "$CONTRACT_BASE_URL/places/nearby?lat=$CONTRACT_GEO_LAT&lng=$CONTRACT_GEO_LNG&radiusMeters=${CONTRACT_RADIUS_METERS:-10000}&category=GAMING") || return 1
    local ids
    ids=$(printf '%s' "$nearby" | jq -r '(.data // [])[] | select(type == "object") | .id // empty' 2>/dev/null | head -20)
    local id
    for id in $ids; do
        local zones
        zones=$(_curl "$CONTRACT_BASE_URL/gaming/places/$id/zones") || continue
        if [ "$(printf '%s' "$zones" | jq -r '(.data // []) | length' 2>/dev/null)" != "0" ]; then
            printf '%s' "$id"
            return 0
        fi
    done
    return 1
}

echo "── стенд: $CONTRACT_BASE_URL"

PLACE_ID=$(find_place_with_zones || true)
if [ -z "$PLACE_ID" ]; then
    echo "✗ не нашёл заведения с игровыми зонами — каталог стенда пуст?"
    echo "  задай CONTRACT_GAMING_PLACE_ID вручную"
    contract_summary
    exit 1
fi
echo "── заведение: $PLACE_ID"

# 1. Зоны заведения. Ручка анонимна (KDoc GamingApi.zones) — без токена
#    приходит 200 с data: [], как меню в «Еде» (issue #9).
probe zones 200 GET "gaming/places/$PLACE_ID/zones" || true

# 2. Места первой зоны: нумерованные позиции, по которым бэкенд считает
#    totalUnits (issue #406). Та же анонимность, что у зон.
ZONE_ID=$(jq -r '(.data // [])[0].id // empty' "$SAMPLES/zones.json" 2>/dev/null || true)
if [ -n "$ZONE_ID" ]; then
    probe units 200 GET "gaming/places/$PLACE_ID/zones/$ZONE_ID/units" || true
else
    skip units "у заведения нет id зоны"
fi

# 3. Свои брони. Ради этого всё и затевалось: ответ под токеном не был снят
#    ни разу (PR #416), 401 на POST gaming/bookings приходит до валидации, а
#    проверить форму ответа можно только через уже существующие брони.
probe_auth my 200 GET "gaming/bookings/my?page=0&size=20" || true

contract_summary
