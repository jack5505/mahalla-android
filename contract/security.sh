#!/usr/bin/env bash
#
# Аккаунтный PIN и продолжение сессии (issue #102): `pin/*`, `auth/session/check`,
# `auth/pin-resume`.
#
#   contract/security.sh
#
# **Проба нарочно только читающая.** `PUT pin/change` сменил бы PIN живого
# аккаунта, `PUT pin/biometric` требует настоящий код, а неверный код у любой
# из них тратит серверную попытку и может залочить аккаунт (лимит ведёт
# бэкенд, issue #51). Автоматически такое дёргать нельзя: цена ошибки —
# человек, запертый вне приложения. Форма их тел взята из `/v3/api-docs` и
# записана в docs/API-CONTRACT.md; проверять руками — на своём аккаунте.
#
# Переменные — см. contract/lib.sh.

set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=contract/lib.sh
source contract/lib.sh

SAMPLES=app/src/test/resources/contract/security
contract_init "$SAMPLES"

echo "── стенд: $CONTRACT_BASE_URL"

DEVICE_ID="${CONTRACT_DEVICE_ID:-contract-harness}"
DEVICE=$(jq -n --arg id "$DEVICE_ID" \
    '{deviceId: $id, platform: "ANDROID", deviceName: "ci-contract-check"}')

# 1. Сначала без токена — это тоже утверждение о контракте, и именно оно
#    решает, на каком Retrofit собирать API. `pin-login` и `setup-pin`
#    анонимные (issue #51), а эти две — нет; ошибись здесь, и 401 на них уйдёт
#    в бесконечный refresh.
probe unauthorized_status 401 GET "pin/status?deviceId=$DEVICE_ID" || true
probe unauthorized_session_check 401 POST auth/session/check \
    "$(jq -n --argjson d "$DEVICE" '{device: $d}')" || true

# 2. Под токеном — только чтение состояния и проверка сессии.
contract_login
probe_auth status 200 GET "pin/status?deviceId=$DEVICE_ID" || true
probe_auth session_check 200 POST auth/session/check \
    "$(jq -n --argjson d "$DEVICE" '{device: $d}')" || true

# 3. Меняющие ручки — руками и на своём аккаунте, см. заголовок файла.
skip change "меняет PIN живого аккаунта"
skip biometric "требует настоящий PIN, неверный тратит серверную попытку"
skip pin_resume "требует настоящий PIN и заблокированную сессию"

contract_summary
