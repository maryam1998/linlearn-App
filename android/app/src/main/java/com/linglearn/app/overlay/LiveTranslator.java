package com.linglearn.app.overlay;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;

/**
 * ترجمه‌ی زنده با همان سیستمِ کیفیتِ اپ (translateFree در app.jsx):
 *   کش → سرویس‌های رایگان → اگر مشکوک بود: بازبینی/اصلاح با AI → اگر هنوز مشکوک بود: ترجمه‌ی مستقیم با AI
 *   و فقط نتیجه‌ی تأییدشده کش می‌شود (همان‌جا که اپ در IndexedDB کش می‌کند).
 */
final class LiveTranslator {
    private static final String TAG = "LiveTranslator";

    /** پشتیبانِ AI (ورکرِ خودِ اپ) — فقط وقتی لازم شود صدا زده می‌شود. */
    interface Ai {
        String translate(String text, String tgt) throws Exception;
        /** متنِ اصلاح‌شده، یا null/"OK" اگر پیش‌نویس درست بود. */
        String verify(String text, String tgt, String draft) throws Exception;
    }

    private LiveTranslator() {}

    static String translate(Context ctx, OkHttpClient http, String text, String src, String tgt, Ai ai) throws Exception {
        if (text == null) throw new IllegalArgumentException("no text");
        final String t = text.trim();
        if (t.isEmpty()) return "";
        final String s = (src == null || src.isEmpty()) ? "auto" : src;
        if (s.equals(tgt)) return t;

        final TransCache cache = TransCache.get(ctx);
        final String cached = cache.get(s, tgt, t);
        if (cached != null) {
            if (!looksLikelyMistranslated(t, cached, tgt, s)) return cached;
            cache.remove(s, tgt, t);                       // کشِ قدیمیِ مشکوک: نادیده + پاک
        }

        String out = null;
        Exception last = null;
        try {
            out = FreeTranslator.translate(http, t, s, tgt);
        } catch (Exception e) {
            last = e;
            Log.d(TAG, "free translators failed: " + e);
        }

        if (out != null && ai != null && looksLikelyMistranslated(t, out, tgt, s)) {
            try {
                String v = ai.verify(t, tgt, out);
                if (v != null) {
                    v = v.trim();
                    if (!v.isEmpty() && !v.matches("(?i)^OK\\.?$")) out = cleanQuotes(v);
                }
            } catch (Exception e) { last = e; }
        }
        if (out != null && looksLikelyMistranslated(t, out, tgt, s)) out = null;   // غلط را نه نشان بده نه کش کن

        if (out == null && ai != null) {
            try {
                String r = ai.translate(t, tgt);
                if (r != null) {
                    r = cleanQuotes(r.trim());
                    if (!r.isEmpty() && !looksLikelyMistranslated(t, r, tgt, s)) out = r;
                }
            } catch (Exception e) { last = e; }
        }

        if (out == null) throw last != null ? last : new IOException("translation failed");
        cache.put(s, tgt, t, out);
        return out;
    }

    private static String cleanQuotes(String s) {
        return s.replaceAll("^[\"'«»]+|[\"'«».\\s]+$", "").trim();
    }

    // ───────── looksLikelyMistranslated (همتای app.jsx) ─────────

    private static Pattern scriptRangeFor(String lang) {
        switch (lang) {
            case "fa": case "ar": return Pattern.compile("[\\u0600-\\u06FF]");
            case "ru": return Pattern.compile("[\\u0400-\\u04FF]");
            case "zh": return Pattern.compile("[\\u4E00-\\u9FFF]");
            case "ja": return Pattern.compile("[\\u3040-\\u30FF\\u4E00-\\u9FFF]");
            case "ko": return Pattern.compile("[\\uAC00-\\uD7AF]");
            case "hi": return Pattern.compile("[\\u0900-\\u097F]");
            default: return null;
        }
    }

    static boolean looksLikelyMistranslated(String sourceText, String draft, String targetLang, String sourceLang) {
        final String src = sourceText == null ? "" : sourceText.trim();
        final String out = draft == null ? "" : draft.trim();
        if (out.isEmpty()) return true;
        // مبدأ و مقصد فرق دارند ولی خروجی عیناً همان متنِ مبدأ است → ترجمه نشده
        if (sourceLang != null && !"auto".equals(sourceLang) && !sourceLang.equals(targetLang)
                && out.toLowerCase(Locale.ROOT).equals(src.toLowerCase(Locale.ROOT))) return true;
        // رسم‌الخطِ زبانِ مقصد مشخص است ولی اثری از آن در خروجی نیست
        Pattern re = scriptRangeFor(targetLang);
        if (re != null && src.length() > 1 && !re.matcher(out).find()) return true;
        // نسبتِ طولِ غیرعادی (برای زبان‌های CJK که فشرده‌اند حدِ پایین سخت‌گیرانه‌تر نیست)
        boolean cjk = "zh".equals(targetLang) || "ja".equals(targetLang) || "ko".equals(targetLang);
        double ratio = out.length() / (double) Math.max(src.length(), 1);
        double lo = cjk ? 0.08 : 0.25;
        if (src.length() > 3 && (ratio < lo || ratio > 3.5)) return true;
        // یک کلمه‌ی تکراریِ پشتِ‌سرهم (نشانه‌ی خرابیِ سرویس)
        if (out.length() > 20 && out.matches("(?s).*\\b(\\p{L}{2,})(\\s+\\1){4,}\\b.*")) return true;
        return false;
    }
}
