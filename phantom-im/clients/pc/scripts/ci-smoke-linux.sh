#!/usr/bin/env bash
set -euo pipefail

format="${1:?usage: ci-smoke-linux.sh <deb|appimage> <artifact-root>}"
artifact_root="${2:?usage: ci-smoke-linux.sh <deb|appimage> <artifact-root>}"
log_file="${RUNNER_TEMP:-/tmp}/phantom-im-pc-${format}.log"

app_pid=""
package_name=""
installed_package=false

cleanup() {
  if [[ -n "$app_pid" ]] && kill -0 "$app_pid" 2>/dev/null; then
    kill -TERM -- "-$app_pid" 2>/dev/null || kill -TERM "$app_pid" 2>/dev/null || true
    wait "$app_pid" 2>/dev/null || true
  fi
  if [[ "$installed_package" == true && -n "$package_name" ]]; then
    sudo apt-get remove -y "$package_name" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

case "$format" in
  deb)
    archive="$(find "$artifact_root" -maxdepth 1 -name '*.deb' -print -quit)"
    [[ -n "$archive" ]] || { echo "No .deb found under $artifact_root" >&2; exit 1; }
    package_name="$(dpkg-deb -f "$archive" Package)"
    sudo apt-get install -y "$archive"
    installed_package=true
    executable="$(dpkg -L "$package_name" | awk '/^\/usr\/bin\// { print; exit }')"
    [[ -n "$executable" ]] || { echo "No /usr/bin launcher found for $package_name" >&2; exit 1; }
    setsid env WEBKIT_DISABLE_COMPOSITING_MODE=1 NO_AT_BRIDGE=1 "$executable" >"$log_file" 2>&1 &
    ;;
  appimage)
    archive="$(find "$artifact_root" -maxdepth 1 -name '*.AppImage' -print -quit)"
    [[ -n "$archive" ]] || { echo "No .AppImage found under $artifact_root" >&2; exit 1; }
    chmod +x "$archive"
    setsid env APPIMAGE_EXTRACT_AND_RUN=1 WEBKIT_DISABLE_COMPOSITING_MODE=1 NO_AT_BRIDGE=1 \
      "$archive" >"$log_file" 2>&1 &
    ;;
  *)
    echo "Unsupported format: $format" >&2
    exit 2
    ;;
esac

app_pid=$!
port=""
for _ in $(seq 1 60); do
  if ! kill -0 "$app_pid" 2>/dev/null; then
    echo "Phantom IM exited during startup" >&2
    cat "$log_file" >&2 || true
    exit 1
  fi
  port="$(lsof -nP -a -p "$app_pid" -iTCP -sTCP:LISTEN -Fn 2>/dev/null |
    sed -n 's/^n127\.0\.0\.1://p' | head -n 1)"
  [[ -n "$port" ]] && break
  sleep 0.5
done
[[ -n "$port" ]] || {
  echo "No loopback gateway listener appeared for PID $app_pid" >&2
  cat "$log_file" >&2 || true
  exit 1
}

page="$(curl -fsS --noproxy '*' "http://127.0.0.1:$port/")"
if [[ "$page" != *"<title>Phantom IM</title>"* ]]; then
  echo "Gateway page did not contain the Phantom IM title" >&2
  exit 1
fi

echo "Phantom IM $format smoke passed: pid=$app_pid, gatewayPort=$port, installer=$archive"
