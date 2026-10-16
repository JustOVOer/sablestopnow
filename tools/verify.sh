#!/usr/bin/env bash
# 从 bash 跑门禁的薄包装（真正干活的是 verify-all.ps1 ✓ 不重复实现检查 ✓）
# 用法： tools/verify.sh            跑全量
#        tools/verify.sh -Only sketch   只跑一个块
cd "$(dirname "$0")/.." || exit 2
args=()
for a in "$@"; do args+=("$a"); done
if [ "${#args[@]}" -eq 0 ]; then
  cmd //c "powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify-all.ps1"
else
  cmd //c "powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify-all.ps1 ${args[*]}"
fi
