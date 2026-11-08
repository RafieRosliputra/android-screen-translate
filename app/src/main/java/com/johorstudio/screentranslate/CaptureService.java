package com.johorstudio.screentranslate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.service.quicksettings.TileService;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class CaptureService extends Service {

    static final String ACTION_STOP = "com.johorstudio.screentranslate.STOP";
    private static final String CHANNEL = "screen_translate";

    static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private WindowManager wm;
    private TextView button;
    private WindowManager.LayoutParams buttonLp;
    private ResultView resultView;
    private boolean busy;
    private int generation;

    /** OCR dihangatkan sekali dan dikongsi semula (hanya diakses dari thread exec). */
    private final Map<Integer, TextRecognizer> recognizers = new HashMap<>();
    /** Cache terjemahan (LRU, kunci = bahasa + teks asal). */
    private final Map<String, String> cache = new LinkedHashMap<String, String>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 600; }
    };
    private static final int OCR_MAX_EDGE = 1920;

    /** Terjemah offline (ML Kit). Hanya diakses dari thread utama. */
    private final Map<String, com.google.mlkit.nl.translate.Translator> mlTranslators = new HashMap<>();
    private LanguageIdentifier langId;

    /** Masa setiap peringkat untuk satu tekanan butang (uptimeMillis). */
    private static final class Timing {
        volatile long tap, cap, ocr, rough, ds, end;
        private static String d(long ms) { return ms < 0 ? "-" : ms + "ms"; }
        String render() {
            return "Tangkap " + d(cap - tap) + " • OCR " + d(ocr - cap)
                    + "\nOffline " + (rough == 0 ? "-" : "+" + d(rough - ocr))
                    + " • DeepSeek " + (ds == 0 ? "-" : "+" + d(ds - ocr))
                    + "\nJumlah " + (end == 0 ? "…" : d(end - tap));
        }
    }
    private Timing timing = new Timing();

    private MediaProjection projection;
    private VirtualDisplay vd;
    private ImageReader reader;
    private int capW, capH, capDpi;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running || intent == null) return START_NOT_STICKY;

        startForegroundNotification();

        int code = intent.getIntExtra("code", 0);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra("data", Intent.class)
                : intent.getParcelableExtra("data");
        if (data == null) { stopSelf(); return START_NOT_STICKY; }

        try {
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            projection = mpm.getMediaProjection(code, data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { main.post(CaptureService.this::stopSelf); }
            }, main);
            wm = getSystemService(WindowManager.class);
            setupDisplay();
            addButton();
            running = true;
            refreshTile();
            Translator.warm();
            final int ocrType = Prefs.ocr(this);
            prefetchModels(Prefs.lang(this), ocrType);
            exec.execute(() -> {
                try {
                    // proses imej kecil kosong supaya model OCR siap dimuat sebelum butang pertama ditekan
                    recognizer(ocrType).process(InputImage.fromBitmap(
                            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888), 0));
                } catch (Exception ignored) { }
            });
        } catch (Exception e) {
            Toast.makeText(this, "Gagal mula: " + e.getMessage(), Toast.LENGTH_LONG).show();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    // ---------- notifikasi ----------

    private void startForegroundNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Screen Translate",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("Screen Translate aktif")
                .setContentText("Tekan butang terapung untuk terjemah skrin")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Berhenti", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    // ---------- tangkap skrin ----------

    private void realSize(int[] out) {
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        out[0] = dm.widthPixels; out[1] = dm.heightPixels; out[2] = dm.densityDpi;
    }

    private void setupDisplay() {
        int[] s = new int[3];
        realSize(s);
        capW = s[0]; capH = s[1]; capDpi = s[2];
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
        vd = projection.createVirtualDisplay("screen-translate", capW, capH, capDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, main);
    }

    /** Skrin diputar? Cipta semula ImageReader dengan saiz baru. */
    private boolean ensureDisplaySize() {
        int[] s = new int[3];
        realSize(s);
        if (s[0] == capW && s[1] == capH) return false;
        capW = s[0]; capH = s[1]; capDpi = s[2];
        ImageReader old = reader;
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
        vd.resize(capW, capH, capDpi);
        vd.setSurface(reader.getSurface());
        old.close();
        return true;
    }

    private void onButtonTap() {
        if (busy) return;
        busy = true;
        final int gen = ++generation;
        Log.i("ST", "tap gen=" + gen);
        timing = new Timing();
        timing.tap = SystemClock.uptimeMillis();
        button.setVisibility(View.INVISIBLE);   // jangan masuk dalam tangkapan
        Translator.warm();                      // pastikan sambungan ke DeepSeek masih hangat
        main.postDelayed(() -> grab(gen, 0, ensureDisplaySize()), 130);
    }

    private void grab(int gen, int tries, boolean resized) {
        if (gen != generation || reader == null) return;
        Image img = null;
        try { img = reader.acquireLatestImage(); } catch (Exception ignored) { }
        if (img == null && tries < 15) {
            main.postDelayed(() -> grab(gen, tries + 1, resized), 100);
            return;
        }
        if (img == null) {
            busy = false;
            button.setVisibility(View.VISIBLE);
            scheduleDock();
            Toast.makeText(this, "Gagal tangkap skrin, cuba lagi", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap bmp = toBitmap(img);
        img.close();
        timing.cap = SystemClock.uptimeMillis();
        showResult(bmp, gen);
    }

    private Bitmap toBitmap(Image img) {
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer buf = p.getBuffer();
        int w = img.getWidth(), h = img.getHeight();
        int rowPixels = p.getRowStride() / p.getPixelStride();
        Bitmap tmp = Bitmap.createBitmap(rowPixels, h, Bitmap.Config.ARGB_8888);
        tmp.copyPixelsFromBuffer(buf);
        if (rowPixels == w) return tmp;
        Bitmap out = Bitmap.createBitmap(tmp, 0, 0, w, h);
        tmp.recycle();
        return out;
    }

    // ---------- keputusan ----------

    private void showResult(Bitmap bmp, int gen) {
        ResultView rv = new ResultView(this, bmp);
        resultView = rv;
        rv.setLoading("");
        rv.setListener(() -> closeResult(gen));

        int[] s = new int[3];
        realSize(s);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                s[0], s[1],
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        wm.addView(rv, lp);
        rv.requestFocus();
        button.setVisibility(View.GONE);

        final String key = Prefs.apiKey(this);
        final String lang = Prefs.lang(this);
        final String model = Prefs.model(this);
        final int ocr = Prefs.ocr(this);
        final Timing tm = timing;

        exec.execute(() -> {
            try {
                if (key.isEmpty()) throw new UserError("API key DeepSeek belum diisi.\nBuka app Screen Translate dan masukkan key.");
                List<ResultView.Block> all = recognize(bmp, ocr);
                tm.ocr = SystemClock.uptimeMillis();
                Log.i("ST", "ocr blocks=" + all.size() + " ocrMs=" + (tm.ocr - tm.cap));
                // langkau teks yang tak perlu diterjemah (nombor, URL, emel, simbol)
                List<ResultView.Block> work = new ArrayList<>();
                for (ResultView.Block b : all) if (needsTranslation(b.original)) work.add(b);
                if (work.isEmpty()) throw new UserError("Tiada teks untuk diterjemah pada skrin.\nKetik di mana-mana untuk tutup.");

                // cache: yang pernah diterjemah terus keluar
                List<ResultView.Block> pending = new ArrayList<>();
                for (ResultView.Block b : work) {
                    String hit;
                    synchronized (cache) { hit = cache.get(lang + "|" + b.original); }
                    if (hit != null) {
                        b.translated = hit;
                        b.finalDone = true;
                        main.post(() -> { if (gen == generation) rv.addBlock(b); });
                    } else {
                        pending.add(b);
                    }
                }
                Log.i("ST", "work=" + work.size() + " pending=" + pending.size() + " lang=" + lang + " model=" + model + " keyLen=" + key.length());
                if (pending.isEmpty()) {
                    tm.end = SystemClock.uptimeMillis();
                    main.post(() -> { if (gen == generation) rv.setTiming(tm.render()); });
                    return;
                }

                // pass pertama: terjemahan offline (segera), berjalan serentak dengan DeepSeek
                final String roughTarget = mlCode(lang);
                if (roughTarget != null) {
                    main.post(() -> {
                        if (gen != generation) return;
                        for (ResultView.Block b : pending) roughPass(b, roughTarget, gen, rv, tm);
                    });
                }

                final int total = pending.size();
                final AtomicInteger done = new AtomicInteger();
                String[] src = new String[total];
                for (int i = 0; i < total; i++) src[i] = pending.get(i).original;

                Exception failure = null;
                try {
                    Translator.translate(src, lang, key, model, (idx, tr) -> {
                        ResultView.Block b = pending.get(idx);
                        Log.i("ST", "ds item " + idx);
                        synchronized (cache) { cache.put(lang + "|" + b.original, tr); }
                        int d = done.incrementAndGet();
                        main.post(() -> {
                            if (gen != generation) return;
                            b.translated = tr;
                            b.finalDone = true;
                            if (tm.ds == 0) tm.ds = SystemClock.uptimeMillis();
                            rv.addBlock(b);
                            rv.setTiming(tm.render());
                        });
                    });
                } catch (Exception e) {
                    Log.e("ST", "translate failed", e);
                    failure = e;
                }
                Log.i("ST", "translate finished done=" + done.get() + "/" + total + " failure=" + (failure == null ? "none" : failure.getMessage()));
                final int shown = done.get() + (work.size() - total);
                final String err = failure == null ? null : failure.getMessage();
                tm.end = SystemClock.uptimeMillis();
                main.post(() -> {
                    if (gen != generation) return;
                    rv.setProgress("");
                    rv.setTiming(tm.render());
                    if (err == null && !rv.hasBlocks()) rv.setError("DeepSeek tak pulangkan terjemahan.\nKetik di mana-mana untuk tutup.");
                    if (err != null) {
                        if (shown == 0 && !rv.hasBlocks()) rv.setError("Ralat: " + err + "\nKetik di mana-mana untuk tutup.");
                        else Toast.makeText(CaptureService.this, "Sebahagian gagal: " + err, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (UserError e) {
                main.post(() -> { if (gen == generation) rv.setError(e.getMessage()); });
            } catch (Exception e) {
                Log.e("ST", "pipeline failed", e);
                final String m = "Ralat: " + e.getMessage() + "\nKetik di mana-mana untuk tutup.";
                main.post(() -> { if (gen == generation) rv.setError(m); });
            }
        });
    }

    private static final class UserError extends Exception {
        UserError(String m) { super(m); }
    }

    /** Satu recognizer bagi setiap jenis OCR, dicipta sekali dan dikongsi semula. */
    private TextRecognizer recognizer(int ocr) {
        TextRecognizer rec = recognizers.get(ocr);
        if (rec != null) return rec;
        switch (ocr) {
            case 1: rec = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build()); break;
            case 2: rec = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build()); break;
            case 3: rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS); break;
            default: rec = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        }
        recognizers.put(ocr, rec);
        return rec;
    }

    private List<ResultView.Block> recognize(Bitmap bmp, int ocr) throws Exception {
        // kecilkan imej untuk OCR (lebih laju); koordinat dikembalikan ke saiz asal
        float scale = 1f;
        int maxEdge = Math.max(bmp.getWidth(), bmp.getHeight());
        Bitmap in = bmp;
        if (maxEdge > OCR_MAX_EDGE) {
            scale = OCR_MAX_EDGE / (float) maxEdge;
            in = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * scale),
                    Math.round(bmp.getHeight() * scale), true);
        }
        Text t = Tasks.await(recognizer(ocr).process(InputImage.fromBitmap(in, 0)));
        if (in != bmp) in.recycle();
        List<ResultView.Block> out = new ArrayList<>();
        for (Text.TextBlock tb : t.getTextBlocks()) {
            Rect r = tb.getBoundingBox();
            String s = tb.getText().replace('\n', ' ').trim();
            if (r == null || s.isEmpty()) continue;
            Rect full = new Rect(Math.round(r.left / scale), Math.round(r.top / scale),
                    Math.round(r.right / scale), Math.round(r.bottom / scale));
            out.add(new ResultView.Block(full, s));
        }
        return out;
    }

    /** Tak perlu terjemah: tiada huruf (nombor/simbol), URL, atau emel. */
    private static boolean needsTranslation(String s) {
        boolean letter = false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) { letter = true; break; }
        }
        if (!letter) return false;
        if (s.indexOf(' ') < 0 && (s.contains("://") || s.startsWith("www.") || s.matches("\\S+@\\S+\\.\\S+"))) return false;
        return true;
    }

    // ---------- terjemahan offline (ML Kit) ----------

    /** Nama bahasa yang awak taip -> kod ML Kit (null = tak disokong, pass offline dilangkau). */
    private static String mlCode(String name) {
        String n = name.toLowerCase(Locale.ROOT).trim();
        String[][] m = {
                {"melayu", "ms"}, {"malay", "ms"}, {"indonesia", "id"}, {"inggeris", "en"}, {"english", "en"},
                {"cina", "zh"}, {"chinese", "zh"}, {"mandarin", "zh"}, {"jepun", "ja"}, {"japan", "ja"},
                {"korea", "ko"}, {"thai", "th"}, {"arab", "ar"}, {"vietnam", "vi"}, {"tamil", "ta"},
                {"hindi", "hi"}, {"perancis", "fr"}, {"french", "fr"}, {"jerman", "de"}, {"german", "de"},
                {"sepanyol", "es"}, {"spanish", "es"}, {"rusia", "ru"}, {"russian", "ru"}
        };
        for (String[] e : m) if (n.contains(e[0])) return e[1];
        return TranslateLanguage.fromLanguageTag(n);
    }

    private com.google.mlkit.nl.translate.Translator mlTranslator(String src, String target) {
        String k = src + ">" + target;
        com.google.mlkit.nl.translate.Translator t = mlTranslators.get(k);
        if (t == null) {
            t = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(src).setTargetLanguage(target).build());
            mlTranslators.put(k, t);
        }
        return t;
    }

    /** Kenal pasti bahasa blok, terjemah offline, dan paparkan kalau DeepSeek belum sampai. Dipanggil di thread utama. */
    private void roughPass(ResultView.Block b, String target, int gen, ResultView rv, Timing tm) {
        if (langId == null) langId = LanguageIdentification.getClient();
        langId.identifyLanguage(b.original).addOnSuccessListener(tag -> {
            String src = TranslateLanguage.fromLanguageTag(tag);
            if (src == null || src.equals(target)) return;
            mlTranslator(src, target).translate(b.original).addOnSuccessListener(out -> {
                if (gen != generation || b.finalDone || out == null || out.trim().isEmpty()) return;
                Log.i("ST", "rough ok");
                b.translated = out;
                if (tm.rough == 0) tm.rough = SystemClock.uptimeMillis();
                rv.addBlock(b);
                rv.setTiming(tm.render());
            });
        });
    }

    /** Muat turun model offline di latar belakang (sekali sahaja; selepas itu tersimpan dalam phone). */
    private void prefetchModels(String lang, int ocr) {
        String target = mlCode(lang);
        if (target == null) return;
        String[] srcs = ocr == 1 ? new String[]{"ja", "en"} : ocr == 2 ? new String[]{"ko", "en"}
                : ocr == 3 ? new String[]{"en"} : new String[]{"zh", "en"};
        final long t0 = SystemClock.uptimeMillis();
        final AtomicInteger left = new AtomicInteger();
        for (String s : srcs) if (!s.equals(target)) left.incrementAndGet();
        for (String s : srcs) {
            if (s.equals(target)) continue;
            mlTranslator(s, target).downloadModelIfNeeded(new DownloadConditions.Builder().build())
                    .addOnSuccessListener(v -> left.decrementAndGet())
                    .addOnFailureListener(e -> left.decrementAndGet());
        }
    }

    private void closeResult(int gen) {
        if (gen == generation) generation++;   // batalkan kerja yang masih berjalan
        if (resultView != null) {
            try { wm.removeView(resultView); } catch (Exception ignored) { }
            resultView = null;
        }
        busy = false;
        if (button != null) {
            button.setAlpha(1f);
            button.setVisibility(View.VISIBLE);
            scheduleDock();
        }
    }

    // ---------- butang terapung ----------

    private static final long DOCK_DELAY_MS = 2500;
    private boolean docked;
    private boolean sideLeft;
    private int btnSize;
    private android.animation.ValueAnimator slide;
    private final Runnable dockRunnable = this::dockButton;

    /** Kelegapan butang semasa rehat (20–100%), boleh diubah dalam app. */
    private float opacity() {
        return Math.max(20, Math.min(100, Prefs.sp(this).getInt("opacity", 60))) / 100f;
    }

    /** Kedudukan x butang: di tepi skrin, atau separa tersembunyi (docked) di luar skrin. */
    private int edgeX(boolean left, boolean dock) {
        int[] s = new int[3];
        realSize(s);
        int hide = (int) (btnSize * 0.6f);
        if (left) return dock ? -hide : 0;
        return dock ? s[0] - btnSize + hide : s[0] - btnSize;
    }

    private void slideTo(int x) {
        if (slide != null) slide.cancel();
        slide = android.animation.ValueAnimator.ofInt(buttonLp.x, x);
        slide.setDuration(160);
        slide.addUpdateListener(a -> {
            if (button == null) return;
            buttonLp.x = (int) a.getAnimatedValue();
            try { wm.updateViewLayout(button, buttonLp); } catch (Exception ignored) { }
        });
        slide.start();
    }

    /** Selepas rehat sebentar, sorokkan butang ke hujung skrin dan jadikan lut. */
    private void scheduleDock() {
        main.removeCallbacks(dockRunnable);
        if (button != null && button.getVisibility() == View.VISIBLE) {
            main.postDelayed(dockRunnable, DOCK_DELAY_MS);
        }
    }

    private void dockButton() {
        if (button == null || busy) return;
        docked = true;
        button.animate().alpha(opacity()).setDuration(200).start();
        slideTo(edgeX(sideLeft, true));
    }

    private void addButton() {
        float d = getResources().getDisplayMetrics().density;
        btnSize = (int) (40 * d);   // ~70% daripada saiz asal (56dp)

        button = new TextView(this);
        button.setText("文A");
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(12);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xE61565C0);
        bg.setStroke((int) (1.5f * d), 0xFFFFFFFF);
        button.setBackground(bg);
        button.setElevation(6 * d);
        button.setAlpha(opacity());

        int[] s = new int[3];
        realSize(s);
        sideLeft = Prefs.sp(this).getBoolean("btn_left", false);
        buttonLp = new WindowManager.LayoutParams(btnSize, btnSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,   // boleh separa keluar skrin
                PixelFormat.TRANSLUCENT);
        buttonLp.gravity = Gravity.TOP | Gravity.START;
        buttonLp.x = edgeX(sideLeft, false);
        buttonLp.y = Prefs.sp(this).getInt("btn_y", s[1] / 3);

        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        button.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int startX, startY; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        main.removeCallbacks(dockRunnable);
                        if (slide != null) slide.cancel();
                        if (docked) {            // keluarkan dari hujung skrin serta-merta
                            docked = false;
                            buttonLp.x = edgeX(sideLeft, false);
                            try { wm.updateViewLayout(button, buttonLp); } catch (Exception ignored) { }
                        }
                        button.animate().cancel();
                        button.setAlpha(1f);
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = buttonLp.x; startY = buttonLp.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!moved && Math.abs(dx) + Math.abs(dy) > slop * 2) moved = true;
                        if (moved) {
                            buttonLp.x = startX + (int) dx;
                            buttonLp.y = startY + (int) dy;
                            try { wm.updateViewLayout(button, buttonLp); } catch (Exception ignored) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) {
                            int[] sz = new int[3];
                            realSize(sz);
                            sideLeft = buttonLp.x + btnSize / 2 < sz[0] / 2;
                            buttonLp.y = Math.max(0, Math.min(sz[1] - btnSize, buttonLp.y));
                            slideTo(edgeX(sideLeft, false));          // lekat ke tepi terdekat
                            Prefs.sp(CaptureService.this).edit()
                                    .putInt("btn_y", buttonLp.y).putBoolean("btn_left", sideLeft).apply();
                            scheduleDock();
                        } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                            onButtonTap();
                        } else {
                            scheduleDock();
                        }
                        return true;
                }
                return false;
            }
        });
        wm.addView(button, buttonLp);
        scheduleDock();
    }

    // ---------- tamat ----------

    private void refreshTile() {
        try {
            TileService.requestListeningState(this, new ComponentName(this, TranslateTileService.class));
        } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        running = false;
        generation++;
        main.removeCallbacks(dockRunnable);
        if (slide != null) slide.cancel();
        if (resultView != null) { try { wm.removeView(resultView); } catch (Exception ignored) { } resultView = null; }
        if (button != null) { try { wm.removeView(button); } catch (Exception ignored) { } button = null; }
        if (vd != null) { vd.release(); vd = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) { } projection = null; }
        exec.shutdownNow();
        for (TextRecognizer r : recognizers.values()) { try { r.close(); } catch (Exception ignored) { } }
        recognizers.clear();
        for (com.google.mlkit.nl.translate.Translator t : mlTranslators.values()) { try { t.close(); } catch (Exception ignored) { } }
        mlTranslators.clear();
        if (langId != null) { try { langId.close(); } catch (Exception ignored) { } langId = null; }
        refreshTile();
        super.onDestroy();
    }
}
