#!/bin/zsh
set -e
cd "${0:A:h}"
if [[ -x "$PWD/.tools/jdk/bin/java" ]]; then
  export JAVA_HOME="$PWD/.tools/jdk"
fi
if [[ -x "$PWD/.venv/bin/python" ]]; then
  .venv/bin/python -m mac_probe.run_checks
else
  ./gradlew test
fi
./gradlew assembleDebug
print '完成。APK 位于 app/build/outputs/apk/debug/app-debug.apk'
read -r '?按回车关闭窗口。'
