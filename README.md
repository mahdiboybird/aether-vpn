<<<<<<< HEAD
# aether-vpn
Aether (Cloudflare WARP client) - UDP/ WireGuard mode for unrestricted access
=======
# Aether VPN — اتصال بدون سرور (Cloudflare WARP)

یک کلاینت ساده برای **Aether** که از سرورهای رایگان Cloudflare WARP استفاده می‌کند.
نیاز به سرور شخصی، دامنه یا خرید اشتراک ندارد.

## 📥 دانلود APK (اندروید)
دانلود مستقیم:
```
https://raw.githubusercontent.com/mahdiboybird/aether-vpn/main/apk/AetherVPN-debug.apk
```
یا از بخش [Releases](https://github.com/mahdiboybird/aether-vpn/releases) و فایل `DOWNLOAD.md` راهنما.

## نصب و راه‌اندازی
1. فایل APK را دانلود و نصب کن (اجازه «منابع ناشناخته» را بده)
2. برنامه را باز کن → «اتصال»
3. تلگرام → تنظیمات → پروکسی → SOCKS5 → سرور `127.0.0.1` پورت `1819`
4. کال تلگرام را تست کن

## ویژگی‌ها
- ✅ بدون سرور شخصی (از Cloudflare WARP استفاده می‌کند)
- ✅ حالت UDP / WireGuard (`--wg`) — مناسب برای کال/تماس تلگرام
- ✅ ضدّ DPI و فینگرپرینت
- ✅ پروکسی محلی SOCKS5 روی `127.0.0.1:1819`
- ✅ روی Linux / Windows / macOS / Android (Termux) کار می‌کند

## نصب (یک خط — Termux / Linux)
```bash
curl -fsSL https://raw.githubusercontent.com/CluvexStudio/aether/main/aether.sh -o aether.sh && chmod +x aether.sh && ./aether.sh install
```

## اجرا (حالت UDP / WireGuard — برای کال تلگرام)
```bash
aether --wg --scan balanced --bind 127.0.0.1:1819
```

برای حالت پیش‌فرض (MASQUE / HTTP3):
```bash
aether --masque --scan balanced --bind 127.0.0.1:1819
```

## تست اتصال
```bash
curl -x socks5h://127.0.0.1:1819 https://www.cloudflare.com/cdn-cgi/trace
```
خروجی باید شامل `warp=on` باشد.

## اتصال تلگرام (اندروید)
1. روی گوشی Aether را نصب و اجرا کنید (حالت `--wg`)
2. در تنظیمات تلگرام → «استفاده از پروکسی» → نوع: SOCKS5
   - سرور: `127.0.0.1`
   - پورت: `1819`
3. کال تلگرام را تست کنید

## منبع
- پروژه اصلی: https://github.com/CluvexStudio/Aether
- کانال تلگرام سازنده: https://t.me/CluvexStudio

---
ساخته‌شده برای استفادهٔ شخصی — بدون هیچ سرور اختصاصی.
>>>>>>> 8817fc9 (Aether VPN: UDP/WireGuard mode for unrestricted access (no server needed))
