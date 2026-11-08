package com.johorstudio.screentranslate;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private Prefs() {}

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("screen_translate", Context.MODE_PRIVATE);
    }

    static String apiKey(Context c) { return sp(c).getString("api_key", "").trim(); }
    static String lang(Context c) { return sp(c).getString("lang", "Bahasa Melayu").trim(); }
    static String model(Context c) { return sp(c).getString("model", "deepseek-chat").trim(); }
    /** 0 = Cina+Inggeris, 1 = Jepun, 2 = Korea, 3 = Latin sahaja */
    static int ocr(Context c) { return sp(c).getInt("ocr", 0); }
}
