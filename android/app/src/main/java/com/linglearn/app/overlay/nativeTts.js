// ============================================================
// nativeTts.js — پل به موتور TTS نیتیوِ اندروید (Piper/VITS)
//
// وقتی اپ توی Capacitor/Android اجرا می‌شه، BubblePlugin.speak() رو صدا
// می‌زنه. توی مرورگر معمولی (PWA/دسکتاپ) null برمی‌گردونه و کدِ صداکننده
// خودش می‌ره سراغ Web Speech API.
//
// nativeSpeak یک Promise برمی‌گردونه که وقتی پخش تموم شد با true resolve
// می‌شه، و اگه پخش شکست خورد / مدل دانلود نشده بود / لغو شد با false —
// یعنی speechController می‌تونه دقیقاً مثل utter.onend رفتار کنه.
// ============================================================

const getPlugin = () => {
  if (typeof window === "undefined") return null;
  const Cap = window.Capacitor;
  if (!Cap || typeof Cap.isNativePlatform !== "function") return null;
  if (!Cap.isNativePlatform()) return null;
  return (Cap.Plugins && Cap.Plugins.BubblePlugin) || null;
};

// پخش‌های در حال انتظار — nativeStop همه‌شون رو آزاد می‌کنه تا listener/تایمر معلق نمونه
const pending = new Set();
let seq = 0;

export function isNativeTtsAvailable() {
  return !!getPlugin();
}

// Promise → true (پخش کامل شد) / false (شکست، لغو یا در دسترس نبود)
// timeoutMs یه سقفِ امنه که اگه event تموم‌شدن هیچ‌وقت نیومد، بلاک نشیم.
export function nativeSpeak(text, langCode, speed = 1.0, timeoutMs = 60000) {
  const plugin = getPlugin();
  if (!plugin || !text) return null;
  const id = "tts-" + Date.now() + "-" + (++seq);

  return new Promise((resolve) => {
    let settled = false;
    let listener = null;
    let timer = null;

    const finish = (ok) => {
      if (settled) return;
      settled = true;
      pending.delete(finish);
      if (timer) clearTimeout(timer);
      if (listener) { try { listener.remove(); } catch (e) {} listener = null; }
      resolve(ok);
    };
    pending.add(finish);

    // سقف زمانی — اگه event نیومد، خودمون آزاد می‌کنیم
    timer = setTimeout(() => finish(false), Math.max(timeoutMs, text.length * 400));

    // اول listener رو ثبت می‌کنیم، بعد پخش رو شروع می‌کنیم (تا event از دست نره)
    Promise.resolve(
      plugin.addListener("ttsSpeakDone", (data) => {
        // فقط eventِ همین پخش (نه پخشِ قبلیِ لغوشده)
        if (data && data.id && data.id !== id) return;
        finish(true);
      })
    )
      .then((h) => {
        listener = h;
        if (settled) {
          try { h.remove(); } catch (e) {}
          listener = null;
          return;
        }
        return plugin.speak({ text, lang: langCode, speed, id });
      })
      .catch(() => finish(false));
  });
}

export function nativeStop() {
  const plugin = getPlugin();
  if (!plugin) return;
  // همه‌ی پخش‌های منتظر رو با false آزاد کن (لغو)
  Array.from(pending).forEach((fn) => fn(false));
  try {
    const r = plugin.stopSpeaking && plugin.stopSpeaking();
    if (r && typeof r.catch === "function") r.catch(() => {});
  } catch (e) {}
}
