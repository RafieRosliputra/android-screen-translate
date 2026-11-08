package com.johorstudio.screentranslate;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** Aktiviti lutsinar: semak kebenaran overlay, minta kebenaran tangkap skrin, kemudian mulakan CaptureService. */
public class ProjectionActivity extends Activity {
    private static final int REQ = 1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (CaptureService.running) {
            Toast.makeText(this, "Screen Translate sudah aktif", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Beri kebenaran 'Paparan atas app lain' dulu", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        MediaProjectionManager m = getSystemService(MediaProjectionManager.class);
        Intent ci = Build.VERSION.SDK_INT >= 34
                ? m.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : m.createScreenCaptureIntent();
        startActivityForResult(ci, REQ);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ && res == RESULT_OK && data != null) {
            Intent s = new Intent(this, CaptureService.class)
                    .putExtra("code", res)
                    .putExtra("data", data);
            startForegroundService(s);
        } else {
            Toast.makeText(this, "Kebenaran tangkap skrin ditolak", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
