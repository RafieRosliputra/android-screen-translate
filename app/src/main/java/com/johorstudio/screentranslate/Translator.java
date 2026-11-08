package com.johorstudio.screentranslate;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Terjemah senarai teks guna DeepSeek (serasi OpenAI) dengan streaming dan permintaan selari.
 * Format jawapan: satu baris setiap item  "<nombor>|<terjemahan>"  supaya boleh dipaparkan sebaik baris siap.
 */
final class Translator {
    private Translator() {}

    interface Callback { void onItem(int index, String translated); }

    private static final String HOST = "https://api.deepseek.com";
    private static final String ENDPOINT = HOST + "/chat/completions";

    /** Sambung TLS awal supaya permintaan sebenar tak perlu handshake. Jangan disconnect: sambungan dikongsi semula. */
    static void warm() {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(HOST).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                int code = c.getResponseCode();
                InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
                drain(is);
            } catch (Exception ignored) { }
        }, "st-warm").start();
    }

    /** Blok sehingga semua bahagian siap. Callback dipanggil dari thread pekerja. */
    static void translate(String[] items, String lang, String key, String model, Callback cb) throws Exception {
        int n = items.length;
        long total = 0;
        for (String s : items) total += s.length();
        int chunks = (n <= 6 || total < 350) ? 1 : (n <= 14 ? 2 : 3);

        // bahagi ikut jumlah aksara supaya seimbang
        List<int[]> ranges = new ArrayList<>();
        long target = total / chunks, acc = 0;
        int start = 0;
        for (int i = 0; i < n; i++) {
            acc += items[i].length();
            if (ranges.size() < chunks - 1 && acc >= target * (ranges.size() + 1)) {
                ranges.add(new int[]{start, i + 1});
                start = i + 1;
            }
        }
        if (start < n) ranges.add(new int[]{start, n});

        AtomicReference<Exception> error = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int[] r : ranges) {
            Thread t = new Thread(() -> {
                try {
                    runChunk(items, r[0], r[1], lang, key, model, cb);
                } catch (Exception e) {
                    error.compareAndSet(null, e);
                }
            }, "st-translate");
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        if (error.get() != null) throw error.get();
    }

    private static void runChunk(String[] items, int from, int to, String lang, String key, String model,
                                 Callback cb) throws Exception {
        int m = to - from;
        String system = "Translate each numbered line into " + lang + ". "
                + "Output exactly one line per input line in the format <number>|<translation> and nothing else "
                + "(no quotes, no explanations, no blank lines). "
                + "Keep numbers, URLs, emails, usernames and brand names unchanged. "
                + "Use short, natural wording suitable for app/screen text.";
        StringBuilder user = new StringBuilder();
        for (int i = 0; i < m; i++) user.append(i + 1).append('|').append(items[from + i]).append('\n');

        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", system));
        msgs.put(new JSONObject().put("role", "user").put("content", user.toString()));
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", msgs);
        body.put("temperature", 0.3);
        body.put("max_tokens", 4000);
        body.put("stream", true);

        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "text/event-stream");
        c.setRequestProperty("Authorization", "Bearer " + key);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        Log.i("ST", "http " + code + " chunk " + from + "-" + to);
        if (code >= 400) {
            String text = readAll(c.getErrorStream());
            String msg = text;
            try { msg = new JSONObject(text).getJSONObject("error").getString("message"); } catch (Exception ignored) { }
            if (code == 401) msg = "API key tidak sah (401). " + msg;
            else if (code == 402) msg = "Baki DeepSeek tak cukup (402). " + msg;
            throw new Exception(msg.length() > 200 ? msg.substring(0, 200) : msg);
        }

        StringBuilder buf = new StringBuilder();
        StringBuilder all = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.equals("[DONE]")) break;
                String piece;
                try {
                    JSONObject ch = new JSONObject(payload).getJSONArray("choices").getJSONObject(0);
                    JSONObject delta = ch.optJSONObject("delta");
                    piece = delta == null ? "" : delta.optString("content", "");
                } catch (Exception e) { continue; }
                if (piece.isEmpty()) continue;
                buf.append(piece);
                all.append(piece);
                int nl;
                while ((nl = buf.indexOf("\n")) >= 0) {
                    emit(buf.substring(0, nl), from, m, cb);
                    buf.delete(0, nl + 1);
                }
            }
        }
        if (buf.length() > 0) emit(buf.toString(), from, m, cb);
        Log.i("ST", "stream len=" + all.length() + " head=" + all.substring(0, Math.min(120, all.length())).replace('\n', '/'));
    }

    private static void emit(String line, int from, int m, Callback cb) {
        line = line.trim();
        int bar = line.indexOf('|');
        if (bar <= 0) return;
        int local;
        try { local = Integer.parseInt(line.substring(0, bar).trim()); } catch (NumberFormatException e) { return; }
        if (local < 1 || local > m) return;
        String tr = line.substring(bar + 1).trim();
        if (tr.isEmpty()) return;
        cb.onItem(from + local - 1, tr);
    }

    private static void drain(InputStream is) throws Exception {
        if (is == null) return;
        try (InputStream in = is) {
            byte[] b = new byte[4096];
            while (in.read(b) > 0) { /* buang */ }
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        try (InputStream in = is; ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }
}
