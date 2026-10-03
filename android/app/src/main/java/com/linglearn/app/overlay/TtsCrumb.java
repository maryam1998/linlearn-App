package com.linglearn.app.overlay;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * ردِّ «آخرین مرحله‌ی کارِ موتور Piper».
 * اگه پردازه‌ی native وسطِ کار بمیره (crash / exit)، از روی این فایل می‌فهمیم کجا مرده.
 * مقدارِ "idle" یعنی موقعِ مردن کاری در حال انجام نبوده.
 */
final class TtsCrumb {

    private static volatile File file;

    private TtsCrumb() {}

    static void init(Context ctx) {
        try {
            file = new File(ctx.getApplicationContext().getFilesDir(), "tts_stage.txt");
        } catch (Throwable ignored) {}
    }

    static void mark(String stage) {
        File f = file;
        if (f == null || stage == null) return;
        try (FileOutputStream o = new FileOutputStream(f, false)) {
            o.write(stage.getBytes("UTF-8"));
        } catch (Throwable ignored) {}
    }

    /** متنِ کوتاه‌شده و تک‌خطی برای ثبت توی فایل. */
    static String brief(String t) {
        if (t == null) return "";
        String s = t.replace('\n', ' ').replace('\r', ' ').replace(':', ' ').trim();
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    static String read() {
        File f = file;
        if (f == null || !f.isFile()) return "";
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n, total = 0;
            while ((n = in.read(buf)) > 0 && total < 512) {
                bo.write(buf, 0, n);
                total += n;
            }
            return bo.toString("UTF-8").trim();
        } catch (Throwable e) {
            return "";
        }
    }
}
