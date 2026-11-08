package com.johorstudio.screentranslate;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private EditText keyEdit, langEdit, modelEdit;
    private Spinner ocrSpin;
    private SeekBar opacitySeek;
    private TextView status;

    private static final String[] OCR_LABELS = {
            "Cina + Inggeris (disyorkan)", "Jepun", "Korea", "Latin sahaja (Inggeris/Melayu)"
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float d = getResources().getDisplayMetrics().density;
        int p = (int) (20 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p * 2, p, p);

        TextView title = new TextView(this);
        title.setText("Screen Translate");
        title.setTextSize(26);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Terjemah apa sahaja di skrin guna DeepSeek. Butang terapung → tangkap skrin → terjemahan dilukis atas teks asal.");
        sub.setPadding(0, (int) (4 * d), 0, (int) (16 * d));
        root.addView(sub);

        keyEdit = field(root, "API key DeepSeek", "sk-...", d);
        keyEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyEdit.setText(Prefs.apiKey(this));

        langEdit = field(root, "Terjemah ke bahasa", "Bahasa Melayu", d);
        langEdit.setText(Prefs.lang(this));

        modelEdit = field(root, "Model", "deepseek-chat", d);
        modelEdit.setText(Prefs.model(this));

        label(root, "Bahasa teks pada skrin (untuk OCR)", d);
        ocrSpin = new Spinner(this);
        ocrSpin.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, OCR_LABELS));
        ocrSpin.setSelection(Prefs.ocr(this));
        root.addView(ocrSpin);

        final TextView opLabel = label(root, "", d);
        opacitySeek = new SeekBar(this);
        opacitySeek.setMax(80);   // 20%–100%
        opacitySeek.setProgress(Prefs.sp(this).getInt("opacity", 60) - 20);
        opLabel.setText("Kelegapan butang terapung: " + (opacitySeek.getProgress() + 20) + "%  (butang tersorok ke tepi skrin bila rehat)");
        opacitySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                opLabel.setText("Kelegapan butang terapung: " + (p + 20) + "%  (butang tersorok ke tepi skrin bila rehat)");
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        root.addView(opacitySeek);

        Button perm = button(root, "1. Beri kebenaran 'Paparan atas app lain'", d);
        perm.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));

        Button start = button(root, "2. Mula Screen Translate", d);
        start.setOnClickListener(v -> {
            save();
            if (Prefs.apiKey(this).isEmpty()) {
                Toast.makeText(this, "Isi API key DeepSeek dulu", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, ProjectionActivity.class));
            finish();   // sorokkan app, tinggalkan butang terapung sahaja
        });

        Button stop = button(root, "Berhenti", d);
        stop.setOnClickListener(v -> {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
            status.postDelayed(this::refreshStatus, 400);
        });

        status = new TextView(this);
        status.setPadding(0, (int) (16 * d), 0, (int) (8 * d));
        root.addView(status);

        TextView help = new TextView(this);
        help.setText("Tile Quick Settings: tarik panel atas → ikon pensel → seret 'Screen Translate' ke panel. "
                + "Tekan tile untuk mula/berhenti.\n\n"
                + "Cara guna: tekan butang terapung (文A) → tunggu sekejap → cubit untuk zoom, seret untuk gerak, "
                + "ketik dua kali untuk zoom pantas, ketik kawasan kosong untuk kembali.");
        help.setTextSize(13);
        root.addView(help);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override protected void onResume() { super.onResume(); refreshStatus(); }
    @Override protected void onPause() { save(); super.onPause(); }

    private void save() {
        Prefs.sp(this).edit()
                .putString("api_key", keyEdit.getText().toString().trim())
                .putString("lang", langEdit.getText().toString().trim().isEmpty()
                        ? "Bahasa Melayu" : langEdit.getText().toString().trim())
                .putString("model", modelEdit.getText().toString().trim().isEmpty()
                        ? "deepseek-chat" : modelEdit.getText().toString().trim())
                .putInt("ocr", ocrSpin.getSelectedItemPosition())
                .putInt("opacity", opacitySeek.getProgress() + 20)
                .apply();
    }

    private void refreshStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        status.setText("Kebenaran overlay: " + (overlay ? "✔ OK" : "✘ belum")
                + "\nStatus: " + (CaptureService.running ? "● AKTIF" : "○ berhenti"));
    }

    private TextView label(LinearLayout root, String text, float d) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setPadding(0, (int) (12 * d), 0, 0);
        root.addView(t);
        return t;
    }

    private EditText field(LinearLayout root, String label, String hint, float d) {
        label(root, label, d);
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        root.addView(e);
        return e;
    }

    private Button button(LinearLayout root, String text, float d) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setAllCaps(false);
        bt.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (12 * d);
        root.addView(bt, lp);
        return bt;
    }
}
