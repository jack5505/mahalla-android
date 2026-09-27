#!/usr/bin/env bash
#
# Поиск по индексу (issue #387): `GET search`.
#
#   contract/search.sh
#
# Зачем отдельная проба. `CatalogApi.search` был объявлен `ApiResponse<List<…>>`
# ещё с issue #53 (2026-08-29), а бэкенд с issue jack5505/mahalla#204
# (2026-09-09) отдаёт страницу (`PageResponse`) — `data` стал объектом
# `{content, page, size, totalElements, …}`, а не массивом. Разбор молча падал
# в `ApiError.Serialization`, а фоллбэк на кэш маскировал это как «показаны
# сохранённые данные» три недели подряд. Единственный способ не наступить на
# это ещё раз — снять живой ответ и сверить его форму `SearchContractTest`.
#
# Переменные — см. contract/lib.sh.

set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=contract/lib.sh
source contract/lib.sh

SAMPLES=app/src/test/resources/contract/search
contract_init "$SAMPLES"

# Слова подобраны по сидовым данным стенда (issue #53): `ox` ни с чем не
# совпадает (символы есть только в середине слов, а поиск — по префиксу или
# токену), `Bog` совпадает с «Milliy Bog'». Не задача этого скрипта решать,
# как именно бэкенд ищет — только зафиксировать, что оба исхода бывают.
SEARCH_EMPTY_QUERY="${CONTRACT_SEARCH_EMPTY_QUERY:-ox}"
SEARCH_HIT_QUERY="${CONTRACT_SEARCH_HIT_QUERY:-Bog}"

echo "── стенд: $CONTRACT_BASE_URL"

# 1. Пустая выдача — ровно симптом issue #387: запрос без единого совпадения
#    должен дать `content: []`, а не HTML или пустой массив вместо объекта.
probe search_empty 200 GET "search?query=$SEARCH_EMPTY_QUERY" || true

# 2. Непустая выдача — форма элемента `content[]` (PlaceDocument).
probe search_hits 200 GET "search?query=$SEARCH_HIT_QUERY" || true

# 3. Без гео-заголовков ручка отвечает отказом (в отличие от `categories`) —
#    закрепляем это явно, а не как случайно не пройденный шаг.
_geo_before="$CONTRACT_GEO_HEADERS"
CONTRACT_GEO_HEADERS=0
probe search_without_geo 403 GET "search?query=$SEARCH_HIT_QUERY" || true
CONTRACT_GEO_HEADERS="$_geo_before"

contract_summary
