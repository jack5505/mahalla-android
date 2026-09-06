#!/usr/bin/env bash
# Stop-хук для claude-code-action: не даёт агенту закончить ход на красной
# сборке. Подключён через вход `settings` обоих claude-workflow, поэтому
# действует только в CI — локальные сессии он не трогает.
#
# Коды выхода (семантика Stop-хука):
#   0 — можно заканчивать ход
#   2 — блокировать: stderr уезжает агенту как причина, он продолжает работу
#
# Проверка идёт, только если в этом прогоне менялся код. Ответ на вопрос
# в issue («@claude объясни, как работает X») сборку не запускает.

set -uo pipefail
cat > /dev/null   # проглотить JSON события со stdin

REPO="${CLAUDE_PROJECT_DIR:-$PWD}"
cd "$REPO" || exit 0

STATE="${RUNNER_TEMP:-/tmp}/claude-verify-blocks"
MAX_BLOCKS=3      # после этого пропускаем: 60-минутный job не резиновый

CODE_GLOBS=('*.kt' '*.kts' '*.xml' '*.pro' 'gradle/*' 'gradle.properties')

changed=$(git status --porcelain -- "${CODE_GLOBS[@]}" 2>/dev/null | head -1)
if [ -z "$changed" ]; then
  base=$(git merge-base HEAD origin/main 2>/dev/null)
  if [ -n "$base" ]; then
    changed=$(git diff --name-only "$base"...HEAD -- "${CODE_GLOBS[@]}" 2>/dev/null | head -1)
  fi
fi

if [ -z "$changed" ]; then
  echo "verify-turn: код не менялся, проверять нечего" >&2
  exit 0
fi

blocks=$(cat "$STATE" 2>/dev/null || echo 0)
if [ "$blocks" -ge "$MAX_BLOCKS" ]; then
  echo "verify-turn: уже $blocks блокировки подряд — пропускаю, чтобы не съесть весь job." >&2
  echo "Сборка всё ещё красная: опиши это в отчёте, не выдавай работу за готовую." >&2
  exit 0
fi

if out=$(./gradlew --console=plain testDebugUnitTest assembleDebug 2>&1); then
  rm -f "$STATE"
  exit 0
fi

echo $((blocks + 1)) > "$STATE"
{
  echo "БЛОК: ./gradlew testDebugUnitTest assembleDebug не прошёл."
  echo "Ход не может закончиться на красной сборке — почини и прогони снова."
  echo "Не отключай тест и не сужай проверку, чтобы «стало зелено»."
  echo
  echo "--- последние 80 строк вывода ---"
  printf '%s\n' "$out" | tail -80
} >&2
exit 2
