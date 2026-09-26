#!/usr/bin/env bash
#
# Каталог категорий (issue #378, хвосты — issue #382): `GET categories`.
#
#   contract/categories.sh
#
# Почему отдельная проба, а не строка в booking.sh: по этой ручке рисуются
# плитки главной, чипы фильтра и выбор в анкете продавца, а схема для неё
# снималась не с `/v3/api-docs` (его по хосту стенда нет — nginx отдаёт 404),
# а из исходников бэкенда. Единственный способ убедиться, что имена четырёх
# полей угаданы верно, — снять живой ответ и сверить его с `CategoryDto`
# в `CategoriesContractTest`.
#
# Ручка публичная: без JWT и, вопреки остальному API, без гео-заголовков.
# Обе пробы ниже это и закрепляют — анонимная проба здесь не «за неимением
# токена», а утверждение о контракте.
#
# Переменные — см. contract/lib.sh.

set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=contract/lib.sh
source contract/lib.sh

SAMPLES=app/src/test/resources/contract/categories
contract_init "$SAMPLES"

echo "── стенд: $CONTRACT_BASE_URL"

# 1. Основная фикстура — её и разбирает CategoriesContractTest.
probe categories 200 GET categories || true

# 2. Гео-заголовки ручке не нужны. Проверяем это явно: если завтра она начнёт
#    требовать их, как `places/nearby`, плитки пропадут у всех, кто не дал
#    геолокацию, — а по одной пробе с заголовками это было бы не видно.
CONTRACT_GEO_HEADERS=0
probe categories_without_geo 200 GET categories || true
CONTRACT_GEO_HEADERS=1

contract_summary
