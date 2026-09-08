# shellcheck shell=bash
#
# Общий каркас контрактных проб: дёргает живой стенд и складывает ответы
# в фикстуры, которые потом разбирает JVM-тест (`*ContractTest.kt`).
#
# Зачем два слоя. Проба здесь отвечает на вопрос «ручка вообще отвечает и
# каким кодом», а сверку полей с DTO делает уже Kotlin-тест на сохранённом
# ответе — он гоняется в обычном `testDebugUnitTest` без всякого бэкенда.
# Поэтому результат прогона переживает прогон: фикстуры коммитятся.
#
# Переменные окружения:
#   CONTRACT_BASE_URL     адрес стенда с /api/v1/ на конце
#   CONTRACT_GEO_LAT/LNG  координаты (гео-заголовки обязательны на всех путях)
#   CONTRACT_REFRESH_TOKEN  refresh-токен живого аккаунта; нет — пробы под
#                           токеном пропускаются, анонимные всё равно идут
#   CONTRACT_INSECURE     1 (по умолчанию) — не проверять TLS: у стенда
#                         самоподписанный сертификат (ADR 0005)

set -euo pipefail

CONTRACT_BASE_URL="${CONTRACT_BASE_URL:-https://189-74-96-232.nip.io/api/v1}"
CONTRACT_GEO_LAT="${CONTRACT_GEO_LAT:-41.311081}"
CONTRACT_GEO_LNG="${CONTRACT_GEO_LNG:-69.240562}"
CONTRACT_INSECURE="${CONTRACT_INSECURE:-1}"
CONTRACT_REFRESH_TOKEN="${CONTRACT_REFRESH_TOKEN:-}"

ACCESS_TOKEN=""
PROBES_OK=0
PROBES_FAILED=0
PROBES_SKIPPED=0
FAILURES=()

command -v jq >/dev/null || { echo "нужен jq: brew install jq (в CI уже есть)"; exit 1; }

_curl() {
    local args=(--silent --show-error --max-time 30)
    [ "$CONTRACT_INSECURE" = "1" ] && args+=(--insecure)
    args+=(-H "X-Geo-Lat: $CONTRACT_GEO_LAT" -H "X-Geo-Lng: $CONTRACT_GEO_LNG")
    [ -n "$ACCESS_TOKEN" ] && args+=(-H "Authorization: Bearer $ACCESS_TOKEN")
    curl "${args[@]}" "$@"
}

contract_init() {
    SAMPLES_DIR="$1"
    mkdir -p "$SAMPLES_DIR"
}

# Меняет refresh-токен на access. Без токена не падаем: анонимные пробы
# (услуги, слоты) ценны сами по себе и идут без авторизации.
contract_login() {
    if [ -z "$CONTRACT_REFRESH_TOKEN" ]; then
        echo "· CONTRACT_REFRESH_TOKEN не задан — пробы под токеном пропущены"
        return 0
    fi
    local body
    body=$(jq -n --arg t "$CONTRACT_REFRESH_TOKEN" \
        --argjson lat "$CONTRACT_GEO_LAT" --argjson lng "$CONTRACT_GEO_LNG" \
        '{refreshToken: $t, lat: $lat, lng: $lng,
          device: {deviceId: "contract-harness", platform: "ANDROID",
                   deviceName: "ci-contract-check"}}')
    local out
    out=$(_curl -X POST -H 'Content-Type: application/json' \
        -d "$body" "$CONTRACT_BASE_URL/auth/refresh") || true
    # Токен лежит в data.tokens, а не в data: ответ — AuthResponseDto
    # (AuthApi.kt), у него внутри TokenPairDto. jq глушится намеренно —
    # лежащий стенд отвечает HTML, на котором разбор иначе оборвёт прогон.
    ACCESS_TOKEN=$(printf '%s' "$out" | jq -r '.data.tokens.accessToken // empty' 2>/dev/null || true)
    if [ -z "$ACCESS_TOKEN" ]; then
        # Тело не печатаем: в удачном ответе лежат свежий access и уже
        # провёрнутый refresh, а маскирует GitHub только исходный секрет.
        echo "· refresh не дал токен — пробы под токеном пропущены. Причина:" \
            "$(printf '%s' "$out" | jq -r '.error.code // .error.message // "ответ не разобрался"' 2>/dev/null || echo 'ответ не разобрался')"
        return 0
    fi
    echo "· токен получен"
}

# probe <имя> <ожидаемый код> <метод> <путь> [тело]
# Ответ кладётся в <SAMPLES_DIR>/<имя>.json как есть — его разбирает Kotlin.
# Принимает несколько ожидаемых кодов через запятую: «200,201» — Spring на
# создание отвечает то так, то так, и это не расхождение контракта.
probe() {
    local name="$1" expected="$2" method="$3" path="$4" body="${5:-}"
    local file="$SAMPLES_DIR/$name.json"
    # Пишем во временный файл и переносим только на успехе: иначе неудачная
    # проба затрёт уже снятую и закоммиченную фикстуру телом ошибки.
    local tmp
    tmp=$(mktemp)
    local args=(-o "$tmp" -w '%{http_code}' -X "$method")
    [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")

    local status
    status=$(_curl "${args[@]}" "$CONTRACT_BASE_URL/$path") || status=""
    [ -n "$status" ] || status="000"

    local why=""
    case ",$expected," in
        *",$status,"*)
            # Не-JSON в фикстуре бесполезен Kotlin-тесту, но сам факт важен:
            # так уже ловили 413 HTML-страницей от nginx (issue #101).
            jq empty "$tmp" 2>/dev/null || why="$status, но тело не JSON"
            ;;
        *) why="ждали $expected, пришло $status" ;;
    esac

    if [ -n "$why" ]; then
        echo "✗ $name — $why"
        [ -s "$tmp" ] && echo "   тело: $(head -c 300 "$tmp" | tr -d '\n')"
        FAILURES+=("$name ($method /$path): $why")
        PROBES_FAILED=$((PROBES_FAILED + 1))
        rm -f "$tmp"
        return 1
    fi
    mv "$tmp" "$file"
    chmod 644 "$file"  # mktemp даёт 600, а фикстура коммитится
    echo "✓ $name — $status"
    PROBES_OK=$((PROBES_OK + 1))
}

# Проба, которую нечем выполнить. Отдельно от провала: «не проверяли» и
# «проверили, не сошлось» — разные новости.
skip() {
    echo "○ $1 — пропущена ($2)"
    PROBES_SKIPPED=$((PROBES_SKIPPED + 1))
}

# Проба, которой нужен токен: без него считается пропущенной, а не упавшей.
probe_auth() {
    if [ -z "$ACCESS_TOKEN" ]; then
        skip "$1" "нет токена"
        return 0
    fi
    probe "$@"
}

contract_summary() {
    # Отметка о прогоне пишется здесь, а не в начале: иначе прогон по лежащему
    # стенду проставил бы сегодняшнюю дату фикстурам, снятым месяц назад.
    if [ "$PROBES_OK" -gt 0 ]; then
        jq -n \
            --arg url "$CONTRACT_BASE_URL" \
            --arg at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
            --arg sha "$(git rev-parse --short HEAD 2>/dev/null || echo unknown)" \
            '{baseUrl: $url, takenAt: $at, commit: $sha}' > "$SAMPLES_DIR/_meta.json"
    fi

    echo
    echo "── итог: ✓$PROBES_OK ✗$PROBES_FAILED ○$PROBES_SKIPPED"
    [ "$CONTRACT_INSECURE" = "1" ] && echo "── TLS не проверялся (самоподписанный сертификат стенда)"
    # Без этого зелёный прогон, где всё пропущено, читается как «сверено».
    if [ "$PROBES_SKIPPED" -gt 0 ]; then
        echo "::warning::пропущено проб: $PROBES_SKIPPED — эти ручки НЕ сверены"
    fi
    if [ "$PROBES_FAILED" -gt 0 ]; then
        printf '   %s\n' "${FAILURES[@]}"
        return 1
    fi
    return 0
}
