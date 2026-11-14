#!/bin/bash

INPUT=$(cat)
COMMAND=$(printf '%s' "$INPUT" | node -e 'let data=""; process.stdin.on("data", chunk => { data += chunk; }); process.stdin.on("end", () => { try { process.stdout.write(JSON.parse(data).tool_input.command || ""); } catch (_) {} });')

DANGEROUS_PATTERNS=(
  "git reset --hard"
  "git clean -fd"
  "git clean -f"
  "git branch -D"
  "git checkout \."
  "git restore \."
  "git push --force"
  "git push -f"
  "push --force"
  "reset --hard"
)

for pattern in "${DANGEROUS_PATTERNS[@]}"; do
  if echo "$COMMAND" | grep -qE "$pattern"; then
    echo "BLOCKED: '$COMMAND' matches dangerous pattern '$pattern'." >&2
    exit 2
  fi
done

exit 0
