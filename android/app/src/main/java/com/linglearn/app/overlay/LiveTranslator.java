package com.linglearn.app.overlay;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;

/**
 * ترجمه‌ی زنده — سریع و بدونِ پرشِ دیدنی:
 *   کش → سرویس‌های رایگان (موازیِ تدریجی، داخلِ FreeTranslator)
 *   اگر نتیجه مشکوک بود (یا سرویس‌ها شکست خوردند یا کند بودند) → «مستقیم» ترجمه‌ی AI، بدونِ مرحله‌ی بازبینیِ جدا.
 *   - اگر رایگان بیش از AI_HEDGE_MS طول کشید، AI «هم‌زمان» شروع می‌شود تا وقتی رایگان مشکوک درآمد، جوابِ AI
 *     از قبل در راه یا آماده باشد (کاربر هیچ‌وقت متنِ مشکوک را نمی‌بیند؛ فقط ترجمه‌ی نهایی می‌آید).
 *   - فقط نتیجه‌ی تأییدشده کش می‌شود (همان‌جا که اپ در IndexedDB کش می‌کند).
 */
final class LiveTranslator {
    private static final String TAG = "LiveTranslator";

    /** اگر ترجمه‌ی رایگان تا این مدت نیامد، ترجمه‌ی AI هم‌زمان شروع می‌شود. */
    private static final long AI_HEDGE_MS = 600;

    private static final ExecutorService POOL = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "live-translate");
            t.setDaemon(true);
            return t;
        }
    });

    /** پشتیبانِ AI (ورکرِ خودِ اپ) — فقط وقتی لازم شود صدا زده می‌شود. */
    interface Ai {
        String translate(String text, String tgt) throws Exception;
        /** (دیگر در مسیرِ زنده استفاده نمی‌شود؛ ترجمه‌ی مستقیمِ AI سریع‌تر است.) */
        String verify(String text, String tgt, String draft) throws Exception;
    }

    private LiveTranslator() {}

    static String translate(Context ctx, OkHttpClient http, final String text, String src, final String tgt, final Ai ai) throws Exception {
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

        final OkHttpClient client = http;
        final Future<String> freeF = POOL.submit(new Callable<String>() {
            @Override public String call() throws Exception { return FreeTranslator.translate(client, t, s, tgt); }
        });
        Future<String> aiF = null;
        String out = null;
        Exception last = null;

        try {
            out = freeF.get(ai != null ? AI_HEDGE_MS : 60_000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            // رایگان کند است → AI را هم‌زمان شروع کن و منتظرِ رایگان هم بمان
            aiF = submitAi(ai, t, tgt);
            try { out = freeF.get(); }
            catch (ExecutionException ee) { last = asException(ee); }
        } catch (ExecutionException ee) {
            last = asException(ee);
            Log.d(TAG, "free translators failed: " + last);
        }

        if (out != null && looksLikelyMistranslated(t, out, tgt, s)) out = null;   // غلط را نه نشان بده نه کش کن

        if (out == null && ai != null) {
            if (aiF == null) aiF = submitAi(ai, t, tgt);
            try {
                String r = aiF.get();
                if (r != null) {
                    r = cleanQuotes(r.trim());
                    if (!r.isEmpty() && !looksLikelyMistranslated(t, r, tgt, s)) out = r;
                }
            } catch (ExecutionException ee) { last = asException(ee); }
        } else if (aiF != null) {
            aiF.cancel(true);                                // رایگانِ سالم رسید؛ AI لازم نیست
        }

        if (out == null) throw last != null ? last : new IOException("translation failed");
        cache.put(s, tgt, t, out);
        return out;
    }

    private static Future<String> submitAi(final Ai ai, final String t, final String tgt) {
        if (ai == null) return null;
        return POOL.submit(new Callable<String>() {
            @Override public String call() throws Exception { return ai.translate(t, tgt); }
        });
    }

    private static Exception asException(ExecutionException ee) {
        Throwable c = ee.getCause();
        return c instanceof Exception ? (Exception) c : new IOException(String.valueOf(c));
    }

    private static String cleanQuotes(String s) {
        return s.replaceAll("^[\"'«»]+|[\"'«».\\s]+$", "").trim();
    }

    // ───────── looksLikelyMistranslated (همتای app.jsx) ─────────

    private static final Pattern REASONING = Pattern.compile(
            "(thinking process|here'?s a thinking|analy[sz]e the (request|text|input)|\\*\\*\\s*analy[sz]e|\\bconstraint\\s*:|the user wants me to|let me (read|think|analy[sz]e|re-?read)|</?think(ing)?>)",
            Pattern.CASE_INSENSITIVE);

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
        // AI گاهی به‌جای ترجمه «فرایندِ فکر کردنش» را می‌نویسد؛ آن را ترجمه حساب نکن
        if (REASONING.matcher(out).find()) return true;
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
