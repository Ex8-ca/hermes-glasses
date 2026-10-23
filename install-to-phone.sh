#!/usr/bin/env bash
# Install the Hermes Glasses APK on the Pixel 10 Pro XL.
#
# The phone is frequently off the LAN, so this polls for it, connects over
# wireless adb, and installs. Safe to re-run.
#
# Usage:
#   ./install-to-phone.sh          # wait up to 5 min, then install
#   ./install-to-phone.sh --wait   # wait indefinitely
#   ./install-to-phone.sh --scan   # just report which Pixel is on the LAN

set -uo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK="${REPO_DIR}/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.chillygeek.hermesglasses"
# Known Pixel 10 Pro XL address from earlier; DHCP may hand it a new one.
KNOWN_IPS=("192.168.1.169")
WAIT_SECONDS=300
SCAN_ONLY=0
FOREVER=0

for arg in "$@"; do
  case "$arg" in
    --wait) FOREVER=1 ;;
    --scan) SCAN_ONLY=1 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

log() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*"; }

if [[ ! -f "$APK" ]]; then
  log "APK missing: $APK"
  log "build it with:"
  log "  cd $REPO_DIR && \\"
  log "    ./gradlew :app:assembleDebug"
  exit 1
fi

log "APK: $(stat -c%s "$APK") bytes, $(stat -c%y "$APK" | cut -d. -f1)"

# The Pixel identifies itself over mDNS as "*-android-<model>._adb-tls-connect._tcp".
find_pixel() {
  # Prefer adb's own mDNS discovery — most reliable when wireless debugging is on.
  local out
  out=$(timeout 15 adb mdns services 2>/dev/null | grep -i "android" | head -5)
  if [[ -n "$out" ]]; then
    echo "$out"
    return 0
  fi
  return 1
}

resolve_ip() {
  local ip="$1"
  if timeout 4 ping -c1 -W1 "$ip" >/dev/null 2>&1; then
    echo "$ip"
    return 0
  fi
  return 1
}

# Scan the subnet for a device that answers adb on 5555.
scan_for_phone() {
  log "scanning LAN for adb devices on :5555..."
  for i in $(seq 100 200); do
    local ip="192.168.1.$i"
    timeout 3 adb connect "$ip:5555" >/dev/null 2>&1
    if timeout 3 adb -s "$ip:5555" shell getprop ro.product.model 2>/dev/null | grep -qi "pixel"; then
      echo "$ip"
      return 0
    fi
  done
  return 1
}

connect_phone() {
  # Already connected?
  if timeout 10 adb devices 2>/dev/null | grep -q "^${PKG}\|device$"; then
    local have
    have=$(timeout 10 adb devices 2>/dev/null | awk '$2=="device"{print $1}' | head -1)
    if [[ -n "$have" ]]; then
      echo "$have"
      return 0
    fi
  fi

  # mDNS-discovered devices.
  local mdns
  mdns=$(find_pixel)
  if [[ -n "$mdns" ]]; then
    local host
    host=$(echo "$mdns" | head -1 | awk -F' ' '{print $NF}')
    log "mdns found: $host"
    timeout 20 adb connect "$host" >/dev/null 2>&1
    if timeout 10 adb devices 2>/dev/null | awk '$2=="device"{print $1}' | grep -q .; then
      timeout 10 adb devices | awk '$2=="device"{print $1}' | head -1
      return 0
    fi
  fi

  # Known addresses first.
  for ip in "${KNOWN_IPS[@]}"; do
    if resolve_ip "$ip" >/dev/null; then
      log "known IP $ip responds; trying adb"
      timeout 20 adb connect "$ip:5555" >/dev/null 2>&1
      if timeout 10 adb devices 2>/dev/null | awk -v t="$ip:5555" '$1==t && $2=="device"{print $1}' | grep -q .; then
        echo "$ip:5555"
        return 0
      fi
    fi
  done

  # Then a subnet scan.
  local found
  found=$(scan_for_phone)
  if [[ -n "$found" ]]; then
    echo "$found:5555"
    return 0
  fi
  return 1
}

if [[ "$SCAN_ONLY" == "1" ]]; then
  log "--- adb devices ---"
  adb devices -l 2>/dev/null
  log "--- mdns ---"
  timeout 15 adb mdns services 2>/dev/null | head -10
  log "--- scan ---"
  found=$(scan_for_phone) && log "found pixel at $found:5555" || log "no pixel found on :5555"
  exit 0
fi

log "waiting for the Pixel..."
deadline=$(( $(date +%s) + WAIT_SECONDS ))
while true; do
  serial=$(connect_phone)
  if [[ -n "$serial" ]]; then
    log "connected: $serial"
    model=$(timeout 10 adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    log "model: $model"
    log "installing..."
    if timeout 180 adb -s "$serial" install -r "$APK"; then
      log "installed. launch with:"
      log "  adb -s $serial shell monkey -p $PKG -c android.intent.category.LAUNCHER 1"
      exit 0
    else
      log "install FAILED"
      exit 1
    fi
  fi
  if [[ "$FOREVER" == "1" ]]; then
    sleep 20
    continue
  fi
  if [[ $(date +%s) -ge $deadline ]]; then
    log "timed out after ${WAIT_SECONDS}s — phone not on the LAN"
    log "make sure: Wi-Fi on, wireless debugging enabled, 'adb connect <ip>:5555' allowed"
    exit 1
  fi
  sleep 15
done
