#!/usr/bin/env bash
#
# Контрактные пробы: общий список заказов «Мои активности» (issue #148).
#
# `GET orders` — единственный из пяти источников «Моих активностей» **без**
# суффикса `/my`, и ни один путь под токеном не был проверен ни разу:
# анонимно все пять отвечают 401, то есть проверено только их существование.
# Проверяет:
#   1. страница без `vertical` — форма ответа (issue #148 п.2: должны
#      приезжать заказы разных вертикалей одним списком, а не только одной);
#   2. `orders/{orderId}` — общая ручка одного заказа отдаёт `OrderView`, а
#      не что-то другое (issue #148 п.3, тот же риск, что в issue #9).
#
# Скоуп по пользователю (issue #148 п.1: чужие заказы не должны приходить)
# эта проба не проверяет — нужен второй живой аккаунт, а
# CONTRACT_REFRESH_TOKEN на стенде один. Сверить руками: снять пробу под
# второй токен и убедиться, что множества id заказов не пересекаются.
#
#   contract/orders.sh
#
# Переменные — см. contract/lib.sh.

set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=contract/lib.sh
source contract/lib.sh

SAMPLES=app/src/test/resources/contract/orders
contract_init "$SAMPLES"
contract_login

echo "── стенд: $CONTRACT_BASE_URL"

# 1. Без `vertical` — заказы всех вертикалей одним списком.
probe_auth all 200 GET "orders?page=0&size=20" || true

# 2. Заказ по общей ручке одного заказа. Предпочитаем FOOD-заказ (issue #9
#    сомневался именно в «Еде»), а если такого нет в первой странице —
#    берём первый попавшийся: путь и схема одни на все вертикали.
ORDER_ID=$(jq -r '
    [(.data.content // [])[] | select(.vertical == "FOOD")][0].id
    // (.data.content // [])[0].id
    // empty
' "$SAMPLES/all.json" 2>/dev/null || true)

if [ -n "$ORDER_ID" ]; then
    probe_auth detail 200 GET "orders/$ORDER_ID" || true
else
    skip detail "в снятой странице нет ни одного id заказа"
fi

contract_summary
