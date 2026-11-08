# Screen Translate (Android)

Terjemah apa sahaja di skrin: butang terapung → tangkap skrin → OCR dalam phone (ML Kit) → terjemah guna DeepSeek API → hasil dilukis atas teks asal.

## Ciri
- Quick Settings tile **Screen Translate** (mula/berhenti)
- Butang terapung (文A): boleh diseret, lut, melekat dan tersorok ke tepi skrin bila rehat
- Hasil boleh di-zoom (cubit, seret, ketik dua kali); ketik kawasan kosong untuk kembali
- Terjemahan offline ML Kit sebagai pass pertama, DeepSeek (streaming, selari) menggantikannya
- Cache terjemahan, OCR Cina/Inggeris, Jepun, Korea, Latin

## Guna
1. Pasang APK, buka app, masukkan **API key DeepSeek** (disimpan dalam phone sahaja, tiada dalam repo).
2. Beri kebenaran *Paparan atas app lain*.
3. Tekan **Mula Screen Translate**, atau guna tile Quick Settings.

## Bina
Perlukan JDK 17 dan Android SDK (platform 35, build-tools 35).

```
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

`make_icon.py` menjana icon (perlu Pillow).
