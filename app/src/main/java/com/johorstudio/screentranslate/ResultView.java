package com.johorstudio.screentranslate;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Paparan penuh: tangkapan skrin beku + teks terjemahan dilukis atas kawasan teks asal.
 * Cubit = zoom, seret = gerak, ketik dua kali = zoom 2.5x, ketik kawasan kosong = tutup.
 */
final class ResultView extends View {

    interface Listener { void onClose(); }

    static final class Block {
        final Rect rect;
        final String original;
        String translated;
        boolean inflated;
        boolean finalDone;      // terjemahan DeepSeek (muktamad) sudah diterima
        int bg = Color.WHITE;
        int fg = Color.BLACK;
        StaticLayout layout;
        Block(Rect r, String o) { rect = r; original = o; }
    }

    private static final float MAX_ZOOM = 8f;

    private final Bitmap bmp;
    private final List<Block> blocks = new ArrayList<>();
    private final Matrix matrix = new Matrix();
    private final float[] mv = new float[9];
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint();
    private final Paint dimPaint = new Paint();
    private final Paint bannerBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bannerText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private float baseScale = 1f;
    private boolean laidOut;
    private String timing = "";      // masa setiap peringkat (untuk ukur kelajuan)
    private String progress = "";    // mesej kecil di atas semasa streaming
    private String status = "";      // mesej tengah (memuatkan / ralat)
    private boolean busy;
    private long hintUntil;
    private Listener listener;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    ResultView(Context ctx, Bitmap bitmap) {
        super(ctx);
        bmp = bitmap;
        density = ctx.getResources().getDisplayMetrics().density;
        dimPaint.setColor(Color.argb(150, 0, 0, 0));
        bannerBg.setColor(Color.argb(210, 20, 20, 20));
        bannerText.setColor(Color.WHITE);
        bannerText.setTextSize(15 * density);
        setFocusableInTouchMode(true);

        scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float cur = currentScale();
                float f = d.getScaleFactor();
                float target = Math.max(baseScale, Math.min(baseScale * MAX_ZOOM, cur * f));
                f = target / cur;
                matrix.postScale(f, f, d.getFocusX(), d.getFocusY());
                clamp();
                invalidate();
                return true;
            }
        });
        gestureDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaleDetector.isInProgress()) return false;
                matrix.postTranslate(-dx, -dy);
                clamp();
                invalidate();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                float cur = currentScale();
                float target = cur > baseScale * 1.3f ? baseScale : baseScale * 2.5f;
                float f = target / cur;
                matrix.postScale(f, f, e.getX(), e.getY());
                clamp();
                invalidate();
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (busy || blocks.isEmpty() || !hitBlock(e.getX(), e.getY())) {
                    if (listener != null) listener.onClose();
                }
                return true;
            }
        });
    }

    void setListener(Listener l) { listener = l; }

    void setLoading(String msg) {
        status = msg;
        busy = true;
        invalidate();
    }

    void setError(String msg) {
        status = msg;
        busy = false;
        blocks.clear();
        invalidate();
    }

    /** Tambah satu blok terjemahan (dipanggil bila baris streaming siap). */
    void addBlock(Block b) {
        status = "";
        busy = false;
        prepare(b);
        if (!blocks.contains(b)) blocks.add(b);   // sudah ada = kemas kini terjemahan (offline -> DeepSeek)
        invalidate();
    }

    boolean hasBlocks() { return !blocks.isEmpty(); }

    void setTiming(String t) {
        timing = t;
        invalidate();
    }

    void setProgress(String p) {
        progress = p;
        if (p.isEmpty()) {
            hintUntil = System.currentTimeMillis() + 3500;
            postInvalidateDelayed(3600);
        }
        invalidate();
    }

    void setResult(List<Block> list) {
        status = "";
        busy = false;
        blocks.clear();
        for (Block b : list) {
            prepare(b);
            blocks.add(b);
        }
        hintUntil = System.currentTimeMillis() + 3500;
        invalidate();
        postInvalidateDelayed(3600);
    }

    // ---------- lukisan ----------

    /** Ambil warna latar dari tepi kotak, tentukan warna teks, dan muatkan teks ke dalam kotak. */
    private void prepare(Block b) {
        Rect r = new Rect(b.rect);
        if (!b.inflated) {
            r.inset(-(int) (2 * density), -(int) (2 * density));
            r.intersect(0, 0, bmp.getWidth(), bmp.getHeight());
            b.rect.set(r);
            b.inflated = true;
        }

        int[] xs = {r.left, r.centerX(), r.right - 1};
        int[] ys = {r.top, r.centerY(), r.bottom - 1};
        long sr = 0, sg = 0, sb = 0;
        int n = 0;
        for (int xi = 0; xi < 3; xi++) {
            for (int yi = 0; yi < 3; yi++) {
                if (xi == 1 && yi == 1) continue; // tengah = teks
                int x = Math.max(0, Math.min(bmp.getWidth() - 1, xs[xi]));
                int y = Math.max(0, Math.min(bmp.getHeight() - 1, ys[yi]));
                int p = bmp.getPixel(x, y);
                sr += Color.red(p); sg += Color.green(p); sb += Color.blue(p);
                n++;
            }
        }
        b.bg = Color.rgb((int) (sr / n), (int) (sg / n), (int) (sb / n));
        double lum = 0.299 * Color.red(b.bg) + 0.587 * Color.green(b.bg) + 0.114 * Color.blue(b.bg);
        b.fg = lum > 140 ? Color.rgb(15, 15, 15) : Color.WHITE;

        int pad = (int) (2 * density);
        int w = Math.max(1, r.width() - pad * 2);
        int h = Math.max(1, r.height() - pad * 2);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(b.fg);
        float lo = 6 * density, hi = Math.max(lo, Math.min(h, 60 * density));
        StaticLayout best = null;
        for (int i = 0; i < 9; i++) {
            float mid = (lo + hi) / 2f;
            tp.setTextSize(mid);
            StaticLayout sl = StaticLayout.Builder.obtain(b.translated, 0, b.translated.length(), tp, w)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
            if (sl.getHeight() <= h) { best = sl; lo = mid; } else { hi = mid; }
        }
        if (best == null) {
            tp.setTextSize(lo);
            best = StaticLayout.Builder.obtain(b.translated, 0, b.translated.length(), tp, w)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        }
        b.layout = best;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (!laidOut && w > 0 && h > 0) {
            RectF src = new RectF(0, 0, bmp.getWidth(), bmp.getHeight());
            RectF dst = new RectF(0, 0, w, h);
            matrix.setRectToRect(src, dst, Matrix.ScaleToFit.CENTER);
            baseScale = currentScale();
            laidOut = true;
        }
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(Color.BLACK);
        c.save();
        c.concat(matrix);
        c.drawBitmap(bmp, 0, 0, bitmapPaint);
        for (Block b : blocks) {
            fillPaint.setColor(b.bg);
            c.drawRect(b.rect, fillPaint);
            if (b.layout != null) {
                c.save();
                int pad = (int) (2 * density);
                c.translate(b.rect.left + pad, b.rect.top + pad);
                b.layout.draw(c);
                c.restore();
            }
        }
        c.restore();

        if (busy) c.drawRect(0, 0, getWidth(), getHeight(), dimPaint);
        if (!status.isEmpty()) drawBanner(c, status, getHeight() / 2f);
    }

    private void drawBanner(Canvas c, String text, float cy) {
        int maxW = (int) (getWidth() - 48 * density);
        StaticLayout sl = StaticLayout.Builder.obtain(text, 0, text.length(), bannerText, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
        float pw = 16 * density, ph = 12 * density;
        float w = 0;
        for (int i = 0; i < sl.getLineCount(); i++) w = Math.max(w, sl.getLineWidth(i));
        RectF box = new RectF(getWidth() / 2f - w / 2f - pw, cy - sl.getHeight() / 2f - ph,
                getWidth() / 2f + w / 2f + pw, cy + sl.getHeight() / 2f + ph);
        c.drawRoundRect(box, 14 * density, 14 * density, bannerBg);
        c.save();
        c.translate(getWidth() / 2f - maxW / 2f, cy - sl.getHeight() / 2f);
        sl.draw(c);
        c.restore();
    }

    // ---------- sentuhan ----------

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        gestureDetector.onTouchEvent(e);
        return true;
    }

    private boolean hitBlock(float x, float y) {
        Matrix inv = new Matrix();
        if (!matrix.invert(inv)) return false;
        float[] p = {x, y};
        inv.mapPoints(p);
        int slop = (int) (6 * density);
        for (Block b : blocks) {
            Rect r = new Rect(b.rect);
            r.inset(-slop, -slop);
            if (r.contains((int) p[0], (int) p[1])) return true;
        }
        return false;
    }

    private float currentScale() {
        matrix.getValues(mv);
        return mv[Matrix.MSCALE_X];
    }

    private void clamp() {
        RectF r = new RectF(0, 0, bmp.getWidth(), bmp.getHeight());
        matrix.mapRect(r);
        float dx = 0, dy = 0;
        if (r.width() <= getWidth()) dx = getWidth() / 2f - r.centerX();
        else if (r.left > 0) dx = -r.left;
        else if (r.right < getWidth()) dx = getWidth() - r.right;
        if (r.height() <= getHeight()) dy = getHeight() / 2f - r.centerY();
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
        matrix.postTranslate(dx, dy);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
            if (listener != null) listener.onClose();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
