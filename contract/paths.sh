#!/usr/bin/env bash
#
# Сверка множеств путей: что зовёт приложение против того, что есть у стенда.
#
# Зачем отдельно от contract/booking.sh. Тот проверяет ФОРМУ ответа у путей,
# которые клиент уже знает. Этот отвечает на другой вопрос — какие пути
# вообще существуют по обе стороны, и потому ловит два разных дефекта:
#
#   · клиент зовёт то, чего у бэкенда нет — выдуманная ручка (дважды стоило
#     переделки вертикали, см. docs/TASKS-BACKLOG.md);
#   · у бэкенда появилась ручка, а в коде стоит комментарий «её нет» — так
#     разъехались корзина («Еда») и слоты врача (#179, #181).
#
# Список вызовов клиента снимается разбором аннотаций Retrofit, а не вторым
# списком руками: второй список разъезжается с первым.
#
#   contract/paths.sh                  сверка с живым стендом, отчёт в stdout
#   contract/paths.sh --client-only    только вызовы приложения (без сети):
#                                      в stdout чистый список, по строке на
#                                      путь, счётчики — в stderr
#   contract/paths.sh --write FILE     сверку сложить в FILE как JSON
#
# Переменные окружения:
#   CONTRACT_DOCS_URL      адрес схемы; по умолчанию /v3/api-docs того же
#                          стенда, что у CONTRACT_BASE_URL (contract/lib.sh)
#   CONTRACT_REFRESH_TOKEN refresh-токен живого аккаунта — обязателен для
#                          сверки с сетью: с issue #358 (2026-09-19)
#                          `/v3/api-docs` отдаёт 401 анонимно. Логин — тем же
#                          `contract_login` из contract/lib.sh, что у проб.
#                          Не нужен для `--client-only`.
#   CONTRACT_INSECURE      1 (по умолчанию) — не проверять TLS: у стенда
#                          самоподписанный сертификат (ADR 0005)
#
# Коды возврата — чтобы прогон по расписанию отличал находку от сбоя:
#   0  выдуманных ручек нет
#   1  находка: клиент зовёт то, чего у бэкенда нет, или путь в аннотации
#      начинается с «/» (Retrofit тогда идёт от корня хоста, мимо /api/v1/)
#   2  схему снять не удалось: нет CONTRACT_REFRESH_TOKEN, токен не получен,
#      `/v3/api-docs` ответил 401 или стенд недоступен — отказ авторизации
#      это не находка, сверять не с чем, шаг можно пропустить, не крася прогон
#   3  сломан сам прогон: не разобрался ни один вызов клиента, неверный
#      аргумент, нет jq
#
# Второй список — «у бэкенда есть, клиент не зовёт» — нарочно НЕ повод для
# ненулевого кода возврата: там сотня путей бизнес-панели и admin/*, которые
# мобильному приложению не нужны.

set -euo pipefail

CLIENT_ONLY=0
WRITE_TO=""
while [ $# -gt 0 ]; do
    case "$1" in
        --client-only) CLIENT_ONLY=1; shift ;;
        --write)
            # Без пути или с флагом вместо пути — ошибка вызова (код 3), а не
            # находка: иначе `--write --client-only` молча создаст файл
            # «--client-only», а голый `--write` уйдёт с кодом 1.
            case "${2:-}" in
                ''|-*) echo "--write требует путь к файлу" >&2; exit 3 ;;
            esac
            WRITE_TO="$2"
            # Относительный путь — от каталога, откуда запустили, а не от
            # корня репозитория, куда скрипт сейчас перейдёт.
            case "$WRITE_TO" in /*) ;; *) WRITE_TO="$PWD/$WRITE_TO" ;; esac
            shift 2 ;;
        -h|--help) sed -n '2,46p' "$0"; exit 0 ;;
        *) echo "неизвестный аргумент: $1" >&2; exit 3 ;;
    esac
done

cd "$(dirname "$0")/.."

CONTRACT_BASE_URL="${CONTRACT_BASE_URL:-https://157.173.109.181.nip.io/api/v1}"
# lib.sh допускает адрес и с «/» на конце — срезаем, иначе /api/v1 не отрежется.
CONTRACT_BASE_URL="${CONTRACT_BASE_URL%/}"
CONTRACT_DOCS_URL="${CONTRACT_DOCS_URL:-${CONTRACT_BASE_URL%/api/v1}/v3/api-docs}"
CONTRACT_INSECURE="${CONTRACT_INSECURE:-1}"

command -v jq >/dev/null || { echo "нужен jq: brew install jq (в CI уже есть)" >&2; exit 3; }

# contract_login() — тот же логин, что у проб (CONTRACT_REFRESH_TOKEN →
# ACCESS_TOKEN). --client-only его не вызывает: там сети нет вовсе.
# shellcheck source=contract/lib.sh
source contract/lib.sh

# {placeId} и {id} — один и тот же путь с точки зрения сверки: имя параметра
# у Retrofit и у springdoc совпадать не обязано.
normalize() { sed -E 's/\{[^}]*\}/{}/g; s#/+$##'; }

# Аннотации Retrofit как «МЕТОД путь». Аннотация однострочная (проверено
# grep'ом по app/src/main); query-строка в значении отбрасывается — сверяем
# путь, а не параметры. Нет ни одного совпадения — пустой вывод, а не выход
# по set -e: это решает проверка CLIENT_N ниже.
raw_annotations() {
    find app/src/main -name '*Api.kt' -print0 |
        { xargs -0 grep -hoE '@(GET|POST|PUT|DELETE|PATCH)\("[^"]+"' || true; } |
        sed -E 's/@([A-Z]+)\("([^"]+)"/\1 \2/; s/\?.*$//'
}

# Вызовы приложения. Ведущий «/» здесь не срезается молча: такой путь Retrofit
# шлёт от корня хоста, то есть мимо /api/v1/, — это дефект, и он попадёт в
# «выдуманные» (у стенда пути без префикса хоста нет).
client_paths() {
    raw_annotations | normalize | sort -u
}

# Пути стенда. В схеме они с префиксом /api/v1 — у Retrofit он уже в baseUrl.
# Требует $ACCESS_TOKEN — вызывающий код логинится заранее и отличает отказ
# авторизации (код 2) от находки: пустой ACCESS_TOKEN сюда не долетает.
server_docs_paths() {
    local args=(--silent --show-error --max-time 60 -H "Authorization: Bearer $ACCESS_TOKEN")
    [ "$CONTRACT_INSECURE" = "1" ] && args+=(--insecure)
    local tmp status
    tmp=$(mktemp)
    trap 'rm -f "$tmp"' RETURN
    status=$(curl "${args[@]}" -o "$tmp" -w '%{http_code}' "$CONTRACT_DOCS_URL") || status="000"

    if [ "$status" = "401" ]; then
        echo "«$CONTRACT_DOCS_URL» ответил 401 с токеном в руках — отказ" \
            "авторизации, а не находка (токен мог протухнуть или не хватает" \
            "прав). Сверять не с чем." >&2
        return 2
    fi
    if [ "$status" != "200" ]; then
        echo "Схему со стенда получить не удалось (код $status, $CONTRACT_DOCS_URL) — сверять не с чем." >&2
        return 2
    fi

    jq -r '.paths | to_entries[] as $p
           | $p.value | keys[]
           | select(test("^(get|post|put|delete|patch)$"))
           | (. | ascii_upcase) + " " + ($p.key | sub("^/api/v1/?"; ""))' "$tmp" |
        normalize |
        sort -u
}

CLIENT=$(client_paths)
CLIENT_N=$(printf '%s\n' "$CLIENT" | grep -c . || true)
DECLARED_N=$(find app/src/main -name '*Api.kt' -print0 |
    { xargs -0 grep -hcE '@(GET|POST|PUT|DELETE|PATCH)\("' || true; } |
    awk '{s+=$1} END {print s+0}')

SUMMARY="Вызовы приложения: $DECLARED_N объявлений, $CLIENT_N уникальных путей"
# В --client-only stdout — это данные (их сравнивают и кладут в фикстуру),
# поэтому счётчики уходят в stderr: в списке путей они были бы ложным вызовом,
# а меняющиеся цифры шумели бы в каждом диффе.
if [ "$CLIENT_ONLY" = "1" ]; then echo "$SUMMARY" >&2; else echo "$SUMMARY"; fi

if [ "$CLIENT_N" -eq 0 ]; then
    echo "Не разобрался ни один вызов — сломан разбор аннотаций, а не контракт." >&2
    exit 3
fi

if [ "$CLIENT_ONLY" = "1" ]; then
    printf '%s\n' "$CLIENT"
    exit 0
fi

if [ -z "$CONTRACT_REFRESH_TOKEN" ]; then
    echo "CONTRACT_REFRESH_TOKEN не задан — «$CONTRACT_DOCS_URL» отдаёт схему" \
        "только с Bearer-токеном (issue #358), анонимно теперь 401. Сверять" \
        "не с чем." >&2
    exit 2
fi

contract_login
if [ -z "$ACCESS_TOKEN" ]; then
    echo "Токен не получен (см. причину выше) — без него «$CONTRACT_DOCS_URL»" \
        "отвечает 401. Сверять не с чем." >&2
    exit 2
fi

# server_docs_paths уже напечатал причину в stderr при отказе — здесь
# второй раз не дублируем, кроме случая «200, но путей не нашлось».
if ! SERVER=$(server_docs_paths); then
    exit 2
fi
if [ -z "$SERVER" ]; then
    echo "Схема стенда пустая ($CONTRACT_DOCS_URL) — путей не нашлось, сверять не с чем." >&2
    exit 2
fi
SERVER_N=$(printf '%s\n' "$SERVER" | grep -c . || true)
SERVER_PATHS_N=$(printf '%s\n' "$SERVER" | awk '{print $2}' | sort -u | grep -c . || true)
echo "Схема стенда ($CONTRACT_DOCS_URL): $SERVER_PATHS_N путей, $SERVER_N операций"

INVENTED=$(comm -23 <(printf '%s\n' "$CLIENT") <(printf '%s\n' "$SERVER"))
UNUSED=$(comm -13 <(printf '%s\n' "$CLIENT") <(printf '%s\n' "$SERVER"))
INVENTED_N=$(printf '%s\n' "$INVENTED" | grep -c . || true)
UNUSED_N=$(printf '%s\n' "$UNUSED" | grep -c . || true)
MATCHED_N=$((CLIENT_N - INVENTED_N))

echo
echo "Совпало:                          $MATCHED_N из $CLIENT_N"
echo "Клиент зовёт, у бэкенда нет:      $INVENTED_N"
echo "У бэкенда есть, клиент не зовёт:  $UNUSED_N"

if [ "$INVENTED_N" -gt 0 ]; then
    echo
    echo "Выдуманные ручки (это дефект, а не запас):"
    printf '%s\n' "$INVENTED" | sed 's/^/  /'
    if printf '%s\n' "$INVENTED" | grep -q '^[A-Z]* /'; then
        echo
        echo "Пути с ведущим «/» Retrofit шлёт от корня хоста, мимо /api/v1/ —"
        echo "уберите «/» в аннотации."
    fi
fi

as_json_array() { printf '%s\n' "$1" | grep . | jq -R . | jq -s .; }

if [ -n "$WRITE_TO" ]; then
    mkdir -p "$(dirname "$WRITE_TO")"
    jq -n \
        --arg url "$CONTRACT_DOCS_URL" \
        --arg date "$(date -u +%Y-%m-%d)" \
        --argjson client "$(as_json_array "$CLIENT")" \
        --argjson server "$(as_json_array "$SERVER")" \
        --argjson invented "$(as_json_array "$INVENTED")" \
        '{takenAt: $date, docs: $url, client: $client, server: $server, invented: $invented}' \
        > "$WRITE_TO"
    # _meta.json рядом — тот же формат, что у contract_summary() в lib.sh,
    # чтобы фикстура паритетов выглядела как соседские (booking/, categories/…).
    jq -n \
        --arg url "$CONTRACT_BASE_URL" \
        --arg at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        --arg sha "$(git rev-parse --short HEAD 2>/dev/null || echo unknown)" \
        '{baseUrl: $url, takenAt: $at, commit: $sha}' \
        > "$(dirname "$WRITE_TO")/_meta.json"
    echo
    echo "записано: $WRITE_TO и $(dirname "$WRITE_TO")/_meta.json"
fi

[ "$INVENTED_N" -eq 0 ]
