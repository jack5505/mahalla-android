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
#   contract/paths.sh --client-only    только вызовы приложения (без сети)
#   contract/paths.sh --write FILE     сверку сложить в FILE как JSON
#
# Переменные окружения:
#   CONTRACT_DOCS_URL   адрес схемы; по умолчанию /v3/api-docs того же стенда,
#                       что у CONTRACT_BASE_URL (contract/lib.sh)
#   CONTRACT_INSECURE   1 (по умолчанию) — не проверять TLS: у стенда
#                       самоподписанный сертификат (ADR 0005)
#
# Коды возврата — чтобы прогон по расписанию отличал находку от сбоя:
#   0  выдуманных ручек нет
#   1  находка: клиент зовёт то, чего у бэкенда нет, или путь в аннотации
#      начинается с «/» (Retrofit тогда идёт от корня хоста, мимо /api/v1/)
#   2  стенд недоступен или отдал не схему — сверять не с чем, шаг можно
#      пропустить, не крася прогон
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
            WRITE_TO="${2:?--write требует путь к файлу}"
            # Относительный путь — от каталога, откуда запустили, а не от
            # корня репозитория, куда скрипт сейчас перейдёт.
            case "$WRITE_TO" in /*) ;; *) WRITE_TO="$PWD/$WRITE_TO" ;; esac
            shift 2 ;;
        -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
        *) echo "неизвестный аргумент: $1" >&2; exit 3 ;;
    esac
done

cd "$(dirname "$0")/.."

CONTRACT_BASE_URL="${CONTRACT_BASE_URL:-https://189-74-96-232.nip.io/api/v1}"
# lib.sh допускает адрес и с «/» на конце — срезаем, иначе /api/v1 не отрежется.
CONTRACT_BASE_URL="${CONTRACT_BASE_URL%/}"
CONTRACT_DOCS_URL="${CONTRACT_DOCS_URL:-${CONTRACT_BASE_URL%/api/v1}/v3/api-docs}"
CONTRACT_INSECURE="${CONTRACT_INSECURE:-1}"

command -v jq >/dev/null || { echo "нужен jq: brew install jq (в CI уже есть)" >&2; exit 3; }

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
server_paths() {
    local args=(--silent --show-error --max-time 60)
    [ "$CONTRACT_INSECURE" = "1" ] && args+=(--insecure)
    curl "${args[@]}" "$CONTRACT_DOCS_URL" |
        jq -r '.paths | to_entries[] as $p
               | $p.value | keys[]
               | select(test("^(get|post|put|delete|patch)$"))
               | (. | ascii_upcase) + " " + ($p.key | sub("^/api/v1/?"; ""))' |
        normalize |
        sort -u
}

CLIENT=$(client_paths)
CLIENT_N=$(printf '%s\n' "$CLIENT" | grep -c . || true)
DECLARED_N=$(find app/src/main -name '*Api.kt' -print0 |
    { xargs -0 grep -hcE '@(GET|POST|PUT|DELETE|PATCH)\("' || true; } |
    awk '{s+=$1} END {print s+0}')

echo "Вызовы приложения: $DECLARED_N объявлений, $CLIENT_N уникальных путей"

if [ "$CLIENT_N" -eq 0 ]; then
    echo "Не разобрался ни один вызов — сломан разбор аннотаций, а не контракт." >&2
    exit 3
fi

if [ "$CLIENT_ONLY" = "1" ]; then
    printf '%s\n' "$CLIENT"
    exit 0
fi

if ! SERVER=$(server_paths) || [ -z "$SERVER" ]; then
    echo "Схему со стенда получить не удалось ($CONTRACT_DOCS_URL) — сверять не с чем." >&2
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
    echo
    echo "записано: $WRITE_TO"
fi

[ "$INVENTED_N" -eq 0 ]
