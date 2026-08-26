#!/bin/bash
# ============================================================
#  Aether VPN — نصب‌کننده اندروید (Termux)
#  برند: Mahdi  |  تلگرام: @HetznerIR
#  بدون سرور، بدون دامنه — از Cloudflare WARP استفاده می‌کند
# ============================================================
clear
echo "============================================="
echo "      Aether VPN  —  توسط Mahdi"
echo "      تلگرام: @HetznerIR"
echo "============================================="
echo ""
echo "[*] به‌روزرسانی Termux..."
pkg update -y -q 2>/dev/null
pkg install -y -q curl 2>/dev/null
echo "[*] دانلود نصاب Aether..."
curl -fsSL https://raw.githubusercontent.com/CluvexStudio/aether/main/aether.sh -o aether.sh
chmod +x aether.sh
echo "[*] نصب Aether..."
./aether.sh install
echo ""
echo "============================================="
echo "  نصب تمام شد ✅"
echo "  برای اجرا (حالت UDP — مناسب کال تلگرام):"
echo ""
echo "      aether --wg --scan balanced \\"
echo "             --bind 127.0.0.1:1819"
echo ""
echo "  سپس در تلگرام: تنظیمات > پروکسی > SOCKS5"
echo "      سرور: 127.0.0.1   پورت: 1819"
echo ""
echo "  تلگرام پشتیبانی: @HetznerIR"
echo "============================================="
