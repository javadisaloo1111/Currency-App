# طلایار (Talayar) — بازار طلا، سکه و ارز

<div dir="rtl">

اپلیکیشن اندرویدی حرفه‌ای و کاملاً فارسی (RTL) برای نمایش قیمت لحظه‌ای طلا، سکه، ارز و رمزارز بازار ایران — به‌همراه درگاه قیمت چندمنبعی (Price Gateway) با Failover خودکار، کارکرد آفلاین، نمودار تعاملی، علاقه‌مندی‌ها و هشدار قیمت.

</div>

---

## Project Overview

**طلایار** یک راه‌حل کامل و Production-Ready برای نمایش قیمت لحظه‌ای بازار ایران است که از سه بخش تشکیل شده:

| بخش | مسیر | توضیح |
|---|---|---|
| 📱 Android App | [`android/`](android/) | Kotlin + Jetpack Compose + Material 3 (RTL، فارسی، آفلاین) |
| 🛡 Price Gateway (Backend) | [`backend/`](backend/) | Fastify + TypeScript + SQLite، Providers با Failover |
| ⚡ Static API (Serverless) | [`scripts/snapshot-publisher.mjs`](scripts/snapshot-publisher.mjs) | Aggregator روی GitHub Actions → GitHub Pages |

```
            Android App (Compose, Room, WorkManager)
                 │  Retrofit / HTTPS
                 ▼
           Price API  ←—— GitHub Pages (static, default) یا سرور اختصاصی
                 │
          ┌──────┴──────┐
          │             │
        Cache         Database (SQLite: assets, prices, price_history, price_sources, price_alerts)
          │
          ▼
   Price Aggregator (اولویت‌بندی، اعتبارسنجی، نرمال‌سازی)
          │
  ┌───────┼────────┬─────────────┐
  ▼       ▼        ▼             ▼
Source A  Source B  Source C   Source D
TGJU     TGJU     Nobitex     Snapshot
(call5)  (mirrors) (crypto)   (gh-pages)
```

**Android Local (Room):** Latest Prices • Price History • Favorites • User Settings • Price Alerts

## Features

<div dir="rtl">

- 💰 قیمت طلای ۱۸ و ۲۴ عیار، مثقال، انس جهانی، سکه امامی/بهار آزادی/نیم/ربع/گرمی — همه به تومان
- 💵 دلار، یورو، پوند، درهم، لیر، یوان، فرانک + تتر و بیت‌کوین
- 🔄 بروزرسانی خودکار (۱۵/۳۰/۶۰ ثانیه، قابل تنظیم) + Pull-to-Refresh
- ⬆️ بروزرسانی درون‌برنامه‌ای: پیدا کردن نسخهٔ جدید از ریلیز رسمی همین مخزن، دانلود با نمایش پیشرفت و نصب با تأیید کاربر (تنظیمات ← بررسی بروزرسانی)
- 📊 نمودار تعاملی قیمت با بازه‌های ۱ساعت تا ۱ سال + Tooltip لمسی
- 🔖 علاقه‌مندی‌ها (ذخیره محلی)
- 🔍 جستجو با پشتیبانی از حروف فارسی/عربی
- 🔔 هشدار قیمت (عبور از حد بالا/پایین، تغییر درصدی) با Notification
- 🌙 حالت تیره/روشن/سیستم + طراحی Material 3 با فونت وزیرمتن
- 📴 کارکرد آفلاین: آخرین قیمت‌ها + «آخرین بروزرسانی» شفاف
- 🏷 نمایش صادقانه Stale Data (`is_stale`) — هرگز داده قدیمی را لحظه‌ای جلوه نمی‌دهد
- 🛡 چندمنبعی با Failover خودکار و Cache آخرین قیمت معتبر

</div>

## Architecture (Android)

MVVM + Clean Architecture + Offline-First:

```
android/app/src/main/java/ir/talayar/app/
├── domain/          # مدل‌ها، اینترفیس Repositoryها، UseCaseها
├── data/
│   ├── remote/      # Retrofit + kotlinx-serialization (DTOs)
│   ├── local/       # Room (entities, DAOs, database)
│   ├── settings/    # DataStore (تم، بازه بروزرسانی، واحد، سرور)
│   └── repository/  # پیاده‌سازی Offline-First
├── core/            # Formatters فارسی، NetworkMonitor
├── worker/          # PriceSyncWorker (15min) + WorkScheduler
├── notifications/   # PriceAlertNotifier
├── di/              # Hilt modules
└── ui/              # theme (Design System) + screens + components
```

## Tech Stack

| لایه | فناوری |
|---|---|
| Android | Kotlin 2.0.21, Jetpack Compose (BOM 2024.12), Material 3 |
| DI | Hilt 2.52 (+ hilt-work, hilt-navigation-compose) |
| Persistence | Room 2.6.1, DataStore Preferences |
| Network | Retrofit 2.11 + OkHttp 4 + kotlinx-serialization |
| Background | WorkManager 2.9 (سینک دوره‌ای + ارزیابی هشدارها) |
| Charts | Custom Compose Canvas (بدون کتابخانه خارجی) |
| Test | JUnit4, kotlinx-coroutines-test, Turbine, Robolectric |
| Backend | Node.js 22, Fastify 5, node:sqlite (بدون وابستگی native) |
| Test (Backend) | Vitest |

## Installation

<div dir="rtl">

**نصب APK:** فایل `app-release.apk` را از [GitHub Release](../../releases/latest) دانلود و نصب کنید (اجازه «نصب از منابع ناشناس» لازم است).

**اجرای پروژه از سورس:**
```bash
cd android
cp local.properties.example local.properties   # مسیر SDK را تنظیم کنید
./gradlew assembleDebug
```

</div>

## Development

```bash
# Android
cd android && ./gradlew test assembleDebug

# Backend
cd backend && npm install
npm run dev        # http://localhost:8080
npm run typecheck && npm test
```

## Build & Release

<div dir="rtl">

Build و Release به‌صورت خودکار روی GitHub Actions انجام می‌شود:

- **CI**: هر push روی `android/**` → `./gradlew clean test assembleRelease` → APK به‌صورت Artifact
- **Release**: با tag (مثلاً `v1.0.0`) → تست، Build، امضا (اگر Secrets تنظیم شده باشند) و انتشار GitHub Release با APK

```bash
git tag v1.0.0 && git push origin v1.0.0
```

امضای حرفه‌ای: چهار Secret تنظیم کنید — `ANDROID_KEYSTORE_BASE64`، `ANDROID_KEYSTORE_PASSWORD`، `ANDROID_KEY_ALIAS`، `ANDROID_KEY_PASSWORD` (جزئیات در [`docs/RELEASE.md`](docs/RELEASE.md)).

</div>

## API Configuration

اپلیکیشن به درگاه قیمت وصل می‌شود؛ پیش‌فرض API ایستای GitHub Pages است و آدرس سرور از داخل اپ هم قابل تغییر است (**تنظیمات ← سرور قیمت**).

**Endpoints** (هر دو حالت `json` و بدون پسوند):

```
GET /api/v1/health
GET /api/v1/market/prices          # همه دارایی‌ها
GET /api/v1/market/gold            # طلا
GET /api/v1/market/coins           # سکه
GET /api/v1/market/currencies      # ارز
GET /api/v1/market/crypto          # رمزارز
GET /api/v1/market/assets/{symbol} # جزئیات یک دارایی
GET /api/v1/market/history/{symbol}# تاریخچه (همه بازه‌ها یا ?range=1H)
```

نمونه Response:

```json
{
  "symbol": "GOLD_18K",
  "name": "طلای ۱۸ عیار",
  "category": "gold",
  "price": 6703000,
  "currency": "TOMAN",
  "unit": "گرم",
  "change": 115000,
  "change_percent": 1.75,
  "day_high": 6710000,
  "day_low": 6580000,
  "prev_price": 6588000,
  "updated_at": "2026-09-09T09:12:31Z",
  "source": "tgju",
  "is_stale": false
}
```

## Environment Variables

<div dir="rtl">

**Backend** — [`backend/.env.example`](backend/.env.example) را به `.env` کپی کنید:

| متغیر | پیش‌فرض | توضیح |
|---|---|---|
| `PORT` / `HOST` | `8080` / `0.0.0.0` | پورت HTTP |
| `POLL_INTERVAL_MS` | `15000` | فاصله Poll منابع (لحظه‌ای: ۱۵ ثانیه) |
| `STALE_AFTER_MS` | `300000` | آستانه Stale شدن |
| `TGJU_HOSTS` | چند mirror | ترتیب Failover منبع اصلی |
| `NOBITEX_ENABLED` | `true` | منبع رمزارز |
| `SNAPSHOT_DIR` / `SNAPSHOT_URL` | — | منبع Snapshot (Bootstrap/Fallback) |
| `RATE_LIMIT_MAX` | `120` | محدودیت درخواست per-minute |

**Android** — [`android/local.properties.example`](android/local.properties.example) (فقط `sdk.dir`)؛ با `-PapiBaseUrl=...` می‌توان URL پیش‌فرض Build را عوض کرد. **هیچ Secret یا API Key داخل APK وجود ندارد.**

</div>

## Price Providers

| اولویت | Provider | پوشش | توضیح |
|---|---|---|---|
| 1 | `tgju` | طلا، سکه، ارز | `call5.tgju.org/ajax.json` + mirrorهای call/call2/call4 (Failover خودکار) |
| 2 | `nobitex` | تتر، بیت‌کوین | API عمومی بازار آمار نوبیتکس (ریال → تومان) |
| 90 | `snapshot` | همه | Snapshot ایستای gh-pages — Bootstrap و Fallback نهایی |

<div dir="rtl">

- هر Provider اینترفیس `PriceProvider` را پیاده می‌کند — افزودن منبع جدید = یک فایل جدید.
- اعتبارسنجی: قیمت مثبت، تغییرات غیرممکن (>۲۵٪ در یک گام) رد می‌شوند و آخرین قیمت معتبر Cache می‌ماند.
- سلامت هر منبع (وضعیت، آخرین موفقیت، تعداد خطا، زمان پاسخ) در `price_sources` و `/api/v1/health` ثبت می‌شود.

</div>

## Offline Mode & Caching

<div dir="rtl">

- **سرور:** Cache در حافظه + SQLite؛ اگر همه Providerها قطع شوند، آخرین قیمت معتبر با `is_stale=true` و timestamp اصلی سرو می‌شود.
- **اپ:** Room آخرین قیمت‌ها + تاریخچه را نگه می‌دارد؛ در حالت آفلاین همان داده‌ها با بنر «اتصال به اینترنت برقرار نیست» و «آخرین بروزرسانی» نمایش داده می‌شود.
- نمایش تغییرات: ↑ افزایش، ↓ کاهش، — بدون تغییر.

</div>

## Testing

<div dir="rtl">

| مجموعه | ابزار | پوشش |
|---|---|---|
| Backend | Vitest (۲۲ تست) | نگاشت TGJU، Failover و Merge، Outlier، History، API Endpoints، Alerts |
| Android Unit | JUnit + coroutines-test | Formatters فارسی/جلالی، Repository (Refresh/Offline/Favorites/Alerts)، ViewModelهای Home/Market/Favorites/Detail |
| Android DB/UI | Robolectric | DAOهای Room (قیمت، تاریخچه، علاقه‌مندی، هشدار)، Compose UI (رندر Home، Dark Mode، Error/Retry) |

```bash
cd android && ./gradlew test     # در CI اجرا می‌شود
cd backend && npm test
```

</div>

## Deployment

<div dir="rtl">

**API ایستا (پیش‌فرض، بدون سرور):** خودکار — GitHub Actions هر ۱۰ دقیقه اجرا می‌شود (`.github/workflows/snapshot.yml`)، قیمت‌ها را Agregate می‌کند و روی GitHub Pages منتشر می‌کند.

**سرور اختصاصی (بروزرسانی لحظه‌ای ۱۵ ثانیه‌ای):**

```bash
cd backend
cp .env.example .env
docker compose up -d --build
# سپس در اپ: تنظیمات ← سرور قیمت ← https://your-domain
```

پشت Caddy/Nginx با HTTPS قرار دهید (اپ فقط HTTPS قبول می‌کند).

</div>

## Future Roadmap

- [ ] Admin Panel کامل برای مدیریت Sourceها (افزودن/حذف/Priority/فعال‌سازی — ساختار `price_sources` و `/api/v1/admin/sources` آماده است)
- [ ] ارزیابی هشدارها روی سرور + Push Notification (FCM)
- [ ] بازه‌های بیشتر نمودار + مقایسه دارایی‌ها
- [ ] ویجت Home Screen
- [ ] Wear OS companion
- [ ] فعال‌سازی R8/ProGuard پس از تست روی دستگاه‌های بیشتر

## License & Notices

- کد پروژه: MIT
- فونت [Vazirmatn](https://github.com/rastikerdar/vazirmatn) — SIL Open Font License 1.1
- داده‌های قیمت: منابع عمومی بازار ایران (TGJU, Nobitex)

<div dir="rtl">

**سلب مسئولیت:** قیمت‌ها صرفاً اطلاع‌رسانی هستند و پیشنهاد خرید/فروش نیستند.

</div>
