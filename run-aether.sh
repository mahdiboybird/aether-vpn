#!/bin/bash
# Aether VPN launcher — UDP/WireGuard mode (for Telegram calls)
# Usage: ./run-aether.sh
set -e
cd "$(dirname "$0")"

if [ ! -x "./aether" ]; then
  echo "[*] aether binary not found, downloading..."
  ARCH=$(uname -m)
  case "$ARCH" in
    x86_64) F="aether-linux-x86_64.tar.gz" ;;
    aarch64|arm64) F="aether-linux-arm64.tar.gz" ;;
    *) echo "Unsupported arch: $ARCH"; exit 1 ;;
  esac
  curl -fsSL -o aether.tar.gz "https://github.com/CluvexStudio/Aether/releases/download/v1.7.0/$F"
  tar xzf aether.tar.gz
  chmod +x ./aether
fi

echo "[*] Starting Aether in WireGuard (UDP) mode..."
echo "[*] SOCKS5 proxy will be on 127.0.0.1:1819"
./aether --wg --scan balanced --bind 127.0.0.1:1819
