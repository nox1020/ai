# OGBA — Offline Grok-style Bot for Android

**[فارسی](#فارسی) · [English](#english)**

## English

OGBA is a native Android (Kotlin + Jetpack Compose) re-creation of the open-source
[open-grok-bot](https://github.com/Anil-matcha/open-grok-bot) workspace — but **fully offline**.
All inference runs on-device with [llama.cpp](https://github.com/ggml-org/llama.cpp) (JNI/NDK, GGUF models). No API keys, no server.

### Features
- **Bots / personas** – create, edit, delete bots with a name and system prompt.
- **Streaming chat** with the local model; history persisted in Room (SQLite).
- **Per-bot memory** – facts injected into the context (Memory tab or `/remember …`).
- **Approval-gated local tools** – `calc`, `time`, `note`, `notes`. The model (or you via `/calc 2+2`, `/time`, `/note text`, `/notes`) requests a tool; nothing runs until you tap *Approve*.
- **Routines** – periodic prompts (WorkManager, ≥15 min) run on the local model, saved to chat and shown as notifications.
- **Model manager** – one-tap download from Hugging Face (Qwen2.5 0.5B / 1.5B Instruct, Llama 3.2 1B, Gemma 3 1B, Q4_K_M) or import any `.gguf` file. After that, airplane mode is fine.
- **Persian (RTL) and English** UI, follows system / per-app language.

### Install
Download `ogba.apk` from the [latest release](https://github.com/nox1020/ai/releases/latest). arm64 phones (and x86_64 emulators), Android 8.0+. Recommended: ≥4 GB RAM; start with Qwen2.5 0.5B.

### Build
```bash
git clone --depth 1 --branch b5600 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
gradle assembleDebug   # JDK 17 + Android SDK/NDK
```
CI (`.github/workflows/android.yml`) builds debug + release APKs and publishes a GitHub Release with `ogba.apk`.

### Limitations
CPU-only inference (no GPU/NPU yet); small models are limited in quality and Persian fluency (Qwen2.5 / Gemma 3 do best). Downloads run while the app is open. The release APK is signed with a debug key.

## فارسی

<div dir="rtl">

OGBA بازسازی بومی اندروید (Kotlin و Jetpack Compose) از پروژه‌ی متن‌باز
[open-grok-bot](https://github.com/Anil-matcha/open-grok-bot) است، اما **کاملاً آفلاین**.
همه‌ی پردازش با [llama.cpp](https://github.com/ggml-org/llama.cpp) و مدل‌های GGUF روی خود گوشی انجام می‌شود؛ بدون کلید API و بدون سرور.

### امکانات
- **بات‌ها** – ساخت، ویرایش و حذف بات با نام و دستور سیستمی.
- **گفتگوی استریمی** با مدل محلی و ذخیره‌ی تاریخچه در Room.
- **حافظه‌ی هر بات** – از تب حافظه یا با دستور `/remember …`.
- **ابزارهای محلی با تأیید کاربر** – ماشین‌حساب، ساعت، یادداشت. هیچ ابزاری بدون زدن «تأیید» اجرا نمی‌شود.
- **روتین‌ها** – اجرای دوره‌ای دستور روی مدل محلی (حداقل ۱۵ دقیقه) با اعلان.
- **مدیریت مدل** – دانلود یک‌باره از Hugging Face (Qwen2.5 0.5B/1.5B، Llama 3.2 1B، Gemma 3 1B) یا وارد کردن فایل `.gguf`. بعد از آن همه‌چیز آفلاین است.
- **رابط فارسی (راست‌به‌چپ) و انگلیسی.**

### نصب
فایل `ogba.apk` را از [آخرین نسخه](https://github.com/nox1020/ai/releases/latest) دانلود و نصب کنید (اندروید ۸ به بالا، گوشی‌های arm64). برای شروع مدل Qwen2.5 0.5B پیشنهاد می‌شود.

### محدودیت‌ها
پردازش فقط با CPU است؛ کیفیت مدل‌های کوچک محدود است. دانلود مدل فقط وقتی اپ باز است انجام می‌شود.

</div>
