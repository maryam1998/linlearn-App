package com.linglearn.app.overlay;

import android.os.Looper;
import android.util.Log;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * مترجمِ آفلاینِ ML Kit — مشترک بینِ پلاگین (اپِ React)، FreeTranslator (حبابِ زنده/ذره‌بین).
 * مدل‌ها روی دستگاه اجرا می‌شوند: بدون سقف، بدون هزینه، بدون نیاز به سرور.
 * هر جفت‌زبان فقط وقتی استفاده می‌شود که مدلش دانلود شده باشد؛ وگرنه (در پس‌زمینه، فقط روی Wi‑Fi)
 * دانلود را شروع می‌کنیم و همان لحظه از مسیرِ آنلاین ترجمه می‌شود.
 */
final class MlKitTranslator {
    private static final String TAG = "MlKitTranslator";
    private static final int MAX_CLIENTS = 4;
    static final int MAX_CHARS = 400;           // برای متنِ بلند، کیفیتِ آنلاین/AI بهتر است

    /** کلیدِ خاموش/روشن (از JS با mlkitSetEnabled تنظیم می‌شود). */
    static volatile boolean enabled = true;

    private static final Set<String> READY = ConcurrentHashMap.newKeySet();
    private static final Set<String> DOWNLOADING = ConcurrentHashMap.newKeySet();
    private static final Map<String, Translator> CLIENTS = new LinkedHashMap<String, Translator>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Translator> e) {
            if (size() > MAX_CLIENTS) {
                try { e.getValue().close(); } catch (Exception ignored) {}
                return true;
            }
            return false;
        }
    };

    private MlKitTranslator() {}

    static String tag(String lang) {
        if (lang == null || lang.isEmpty() || "auto".equals(lang)) return null;
        return TranslateLanguage.fromLanguageTag(lang);
    }

    static boolean isSupported(String src, String tgt) {
        String s = tag(src), t = tag(tgt);
        return s != null && t != null && !s.equals(t);
    }

    private static String key(String s, String t) { return s + ">" + t; }

    static boolean isReady(String src, String tgt) {
        String s = tag(src), t = tag(tgt);
        return s != null && t != null && READY.contains(key(s, t));
    }

    static boolean isDownloading(String src, String tgt) {
        String s = tag(src), t = tag(tgt);
        return s != null && t != null && DOWNLOADING.contains(key(s, t));
    }

    private static Translator client(String s, String t) {
        String k = key(s, t);
        synchronized (CLIENTS) {
            Translator c = CLIENTS.get(k);
            if (c == null) {
                c = Translation.getClient(new TranslatorOptions.Builder()
                        .setSourceLanguage(s).setTargetLanguage(t).build());
                CLIENTS.put(k, c);
            }
            return c;
        }
    }

    /** آیا مدلِ هر دو زبان (و انگلیسیِ واسط) روی دستگاه هست؟ سنکرون، فقط روی تردِ پس‌زمینه؛ نتیجه در READY ثبت می‌شود. */
    static boolean refreshReadyBlocking(String src, String tgt) {
        final String s = tag(src), t = tag(tgt);
        if (s == null || t == null || s.equals(t)) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) return READY.contains(key(s, t));
        try {
            RemoteModelManager mm = RemoteModelManager.getInstance();
            Set<String> langs = new HashSet<>();
            langs.add(s); langs.add(t); langs.add(TranslateLanguage.ENGLISH);
            for (String l : langs) {
                Boolean ok = Tasks.await(mm.isModelDownloaded(new TranslateRemoteModel.Builder(l).build()), 5, TimeUnit.SECONDS);
                if (ok == null || !ok) { READY.remove(key(s, t)); return false; }
            }
            READY.add(key(s, t));
            return true;
        } catch (Exception e) {
            Log.d(TAG, "refreshReady failed: " + e);
            return false;
        }
    }

    interface Cb { void ok(String result); void fail(Exception e); }
    interface DoneCb { void ok(); void fail(Exception e); }

    /** دانلودِ مدل (اگر لازم باشد). wifiOnly=true برای دانلودِ خودکار در پس‌زمینه. */
    static void download(String src, String tgt, boolean wifiOnly, final DoneCb cb) {
        final String s = tag(src), t = tag(tgt);
        if (s == null || t == null || s.equals(t)) {
            if (cb != null) cb.fail(new IllegalArgumentException("unsupported language pair"));
            return;
        }
        final String k = key(s, t);
        if (!DOWNLOADING.add(k)) { if (cb != null) cb.fail(new IllegalStateException("already downloading")); return; }
        DownloadConditions.Builder b = new DownloadConditions.Builder();
        if (wifiOnly) b.requireWifi();
        client(s, t).downloadModelIfNeeded(b.build())
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override public void onSuccess(Void v) {
                        READY.add(k); DOWNLOADING.remove(k);
                        if (cb != null) cb.ok();
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override public void onFailure(Exception e) {
                        DOWNLOADING.remove(k);
                        Log.w(TAG, "model download failed " + k, e);
                        if (cb != null) cb.fail(e);
                    }
                });
    }

    /** بدونِ بلاک. مدل باید دانلود شده باشد، وگرنه fail. */
    static void translateAsync(String src, String tgt, String text, final Cb cb) {
        final String s = tag(src), t = tag(tgt);
        if (s == null || t == null || s.equals(t)) { cb.fail(new IllegalArgumentException("unsupported language pair")); return; }
        client(s, t).translate(text)
                .addOnSuccessListener(new OnSuccessListener<String>() {
                    @Override public void onSuccess(String r) { cb.ok(r); }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override public void onFailure(Exception e) { cb.fail(e); }
                });
    }

    /** آیا در FreeTranslator/LiveTranslator می‌شود اول از ML Kit استفاده کرد؟ (فقط روی تردِ پس‌زمینه) */
    static boolean canUseOffline(String src, String tgt, String text) {
        if (!enabled || text == null || text.length() > MAX_CHARS) return false;
        if (!isSupported(src, tgt)) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        if (isReady(src, tgt)) return true;
        if (refreshReadyBlocking(src, tgt)) return true;
        // آماده نیست: در پس‌زمینه و فقط روی Wi‑Fi دانلود کن؛ همین‌بار آنلاین ترجمه می‌شود.
        download(src, tgt, true, null);
        return false;
    }

    /** سنکرون، فقط روی تردِ پس‌زمینه. */
    static String translateBlocking(String src, String tgt, String text, long timeoutMs) throws Exception {
        final String s = tag(src), t = tag(tgt);
        if (s == null || t == null) throw new IllegalArgumentException("unsupported language pair");
        Task<String> task = client(s, t).translate(text);
        return Tasks.await(task, timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** حذفِ مدلِ یک زبان از دستگاه. */
    static void deleteModel(String lang, final DoneCb cb) {
        final String l = tag(lang);
        if (l == null) { cb.fail(new IllegalArgumentException("unsupported language")); return; }
        RemoteModelManager.getInstance().deleteDownloadedModel(new TranslateRemoteModel.Builder(l).build())
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override public void onSuccess(Void v) { READY.clear(); cb.ok(); }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override public void onFailure(Exception e) { cb.fail(e); }
                });
    }
}
