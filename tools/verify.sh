#!/usr/bin/env bash
# 从 bash 跑门禁：经 wscript 隐藏启动 ⇒ **不弹窗、不抢焦点** ✓
cd "$(dirname "$0")/.." || exit 2
OUT="$TEMP/dsh-verify-out.txt"
[ -z "$TEMP" ] && OUT="/tmp/dsh-verify-out.txt"
rm -f "$OUT"
if [ "${#@}" -gt 0 ]; then
  cscript //nologo tools/verify-hidden.vbs "$@" >/dev/null 2>&1
else
  cscript //nologo tools/verify-hidden.vbs >/dev/null 2>&1
fi
cat "$OUT" 2>/dev/null
