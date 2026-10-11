package com.linglearn.app.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * متن‌های کادرِ ترجمه‌ی زنده، ذره‌بین و دیالوگ‌های بومیِ اندروید به همه‌ی زبان‌های رابطِ اپ.
 *
 * فارسی و انگلیسی مثلِ قبل مستقیم از خودِ فراخوانی‌ها (msg(fa, en)) می‌آیند؛ اسپانیایی، آلمانی،
 * فرانسوی و عربی با کلیدِ «متنِ انگلیسی» از جدولِ پایین پیدا می‌شوند. اگه ترجمه‌ای پیدا نشد،
 * انگلیسی نشون داده می‌شه (هیچ‌وقت کلیدِ خام یا متنِ خالی نمی‌بینی).
 *
 * جاگذاری‌ها: {0} و {1} داخلِ کلید یعنی «هر متنی» (مثلاً عدد یا اسمِ زبان)؛ همون متن توی
 * ترجمه جایِ {0} و {1} گذاشته می‌شه. پس فراخوانی‌هایی که رشته رو با + می‌سازن هم کار می‌کنن.
 *
 * ترتیبِ ستون‌ها: es, de, fr, ar.
 */
final class NativeI18n {
    private NativeI18n() {}

    static final String PREFS = "bubble_prefs";  // همون PREFSِ BubbleService

    private static final String[] LANGS = {"es", "de", "fr", "ar"};
    private static final Map<String, String[]> EXACT = new HashMap<>();
    private static final List<Tpl> TEMPLATES = new ArrayList<>();

    private static final class Tpl {
        final Pattern p; final String[] out;
        Tpl(Pattern p, String[] out) { this.p = p; this.out = out; }
    }

    private static void put(String en, String es, String de, String fr, String ar) {
        String[] out = {es, de, fr, ar};
        if (en.contains("{0}")) {
            StringBuilder re = new StringBuilder("^");
            Matcher m = Pattern.compile("\\{\\d\\}").matcher(en);
            int last = 0;
            while (m.find()) {
                re.append(Pattern.quote(en.substring(last, m.start()))).append("(.+?)");
                last = m.end();
            }
            re.append(Pattern.quote(en.substring(last))).append("$");
            TEMPLATES.add(new Tpl(Pattern.compile(re.toString(), Pattern.DOTALL), out));
        } else {
            EXACT.put(en, out);
        }
    }

    /** زبانِ رابطِ اپ: یکی از fa / en / es / de / fr / ar (پیش‌فرض: همون رفتارِ قبلی). */
    static String lang(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String ui = sp.getString("uiLang", null);
        if (ui == null || ui.isEmpty()) ui = sp.getString("target", "fa");
        return normalize(ui);
    }

    static String normalize(String l) {
        if (l == null) return "en";
        l = l.trim().toLowerCase(java.util.Locale.ROOT);
        if (l.equals("fa") || l.equals("en") || l.equals("es") || l.equals("de")
                || l.equals("fr") || l.equals("ar")) return l;
        return "en";
    }

    private static int idx(String lang) {
        for (int i = 0; i < LANGS.length; i++) if (LANGS[i].equals(lang)) return i;
        return -1;
    }

    /** fa/en مستقیم؛ بقیه از جدول؛ پیدا نشد → انگلیسی. */
    static String tr(String lang, String fa, String en) {
        if ("fa".equals(lang)) return fa;
        int i = idx(lang);
        if (i < 0 || en == null) return en;
        String[] hit = EXACT.get(en);
        if (hit != null) return hit[i];
        for (Tpl t : TEMPLATES) {
            Matcher m = t.p.matcher(en);
            if (m.matches()) {
                String s = t.out[i];
                for (int g = 1; g <= m.groupCount(); g++) s = s.replace("{" + (g - 1) + "}", m.group(g));
                return s;
            }
        }
        return en;
    }

    static {
        put("⚠ Could not start recording",
            "⚠ No se pudo iniciar la grabación",
            "⚠ Aufnahme konnte nicht gestartet werden",
            "⚠ Impossible de démarrer l’enregistrement",
            "⚠ تعذّر بدء التسجيل");
        put("⚠ Microphone permission required",
            "⚠ Se necesita permiso del micrófono",
            "⚠ Mikrofonberechtigung erforderlich",
            "⚠ Autorisation du microphone requise",
            "⚠ مطلوب إذن الميكروفون");
        put("⚠ Recording failed",
            "⚠ Error al grabar",
            "⚠ Aufnahme fehlgeschlagen",
            "⚠ Échec de l’enregistrement",
            "⚠ فشل التسجيل");
        put("▶ YouTube subtitles",
            "▶ Subtítulos de YouTube",
            "▶ YouTube-Untertitel",
            "▶ Sous-titres YouTube",
            "▶ ترجمات يوتيوب");
        put("🎙 Live translation",
            "🎙 Traducción en vivo",
            "🎙 Live-Übersetzung",
            "🎙 Traduction en direct",
            "🎙 الترجمة الفورية");
        put("Repeat off",
            "Repetición desactivada",
            "Wiederholung aus",
            "Répétition désactivée",
            "التكرار متوقف");
        put("Repeat ∞ — each sentence, then the next",
            "Repetir ∞ — cada frase y luego la siguiente",
            "Wiederholen ∞ — jeder Satz, dann der nächste",
            "Répéter ∞ — chaque phrase, puis la suivante",
            "تكرار ∞ — كل جملة ثم التي تليها");
        put("Each sentence {0}×, then the next",
            "Cada frase {0}×, luego la siguiente",
            "Jeder Satz {0}×, dann der nächste",
            "Chaque phrase {0}×, puis la suivante",
            "كل جملة {0} مرة ثم التالية");
        put("Repeat stopped",
            "Repetición detenida",
            "Wiederholung gestoppt",
            "Répétition arrêtée",
            "توقّف التكرار");
        put("YouTube player not available",
            "El reproductor de YouTube no está disponible",
            "YouTube-Player nicht verfügbar",
            "Le lecteur YouTube n’est pas disponible",
            "مشغّل يوتيوب غير متاح");
        put("Repeat ∞ with YouTube audio",
            "Repetir ∞ con el audio de YouTube",
            "Wiederholen ∞ mit YouTube-Audio",
            "Répéter ∞ avec le son de YouTube",
            "تكرار ∞ بصوت يوتيوب");
        put("Repeat {0}× with YouTube audio",
            "Repetir {0}× con el audio de YouTube",
            "{0}× wiederholen mit YouTube-Audio",
            "Répéter {0}× avec le son de YouTube",
            "تكرار {0} مرة بصوت يوتيوب");
        put("Video restarted from the beginning",
            "El vídeo se reinició desde el principio",
            "Video von vorn gestartet",
            "Vidéo relancée depuis le début",
            "أُعيد تشغيل الفيديو من البداية");
        put("🔖 Saved for next story",
            "🔖 Guardado para la próxima historia",
            "🔖 Für nächste Geschichte gespeichert",
            "🔖 Enregistré pour la prochaine histoire",
            "🔖 تم الحفظ للقصة القادمة");
        put("🔖 Save for next story",
            "🔖 Guardar para la próxima historia",
            "🔖 Für nächste Geschichte speichern",
            "🔖 Enregistrer pour la prochaine histoire",
            "🔖 حفظ للقصة القادمة");
        put("🧩 Saved to grammar",
            "🧩 Guardado en gramática",
            "🧩 In Grammatik gespeichert",
            "🧩 Enregistré dans la grammaire",
            "🧩 تم الحفظ في القواعد");
        put("🧩 Add to grammar learning",
            "🧩 Añadir al aprendizaje de gramática",
            "🧩 Zum Grammatiklernen hinzufügen",
            "🧩 Ajouter à l’apprentissage de la grammaire",
            "🧩 إضافة إلى تعلّم القواعد");
        put("🔁 Added to Leitner box",
            "🔁 Añadido a la caja de Leitner",
            "🔁 Zur Leitner-Box hinzugefügt",
            "🔁 Ajouté à la boîte de Leitner",
            "🔁 أُضيف إلى صندوق لايتنر");
        put("🔁 Add to Leitner box",
            "🔁 Añadir a la caja de Leitner",
            "🔁 Zur Leitner-Box hinzufügen",
            "🔁 Ajouter à la boîte de Leitner",
            "🔁 إضافة إلى صندوق لايتنر");
        put("No translation — saving still works",
            "Sin traducción — aun así se puede guardar",
            "Keine Übersetzung — Speichern funktioniert trotzdem",
            "Pas de traduction — l’enregistrement fonctionne quand même",
            "لا توجد ترجمة — الحفظ ما زال ممكنًا");
        put("Save failed",
            "Error al guardar",
            "Speichern fehlgeschlagen",
            "Échec de l’enregistrement",
            "فشل الحفظ");
        put("Turn on Notification access for this app to change playback speed.",
            "Activa el «Acceso a notificaciones» para esta app para cambiar la velocidad de reproducción.",
            "Aktiviere den „Benachrichtigungszugriff“ für diese App, um die Wiedergabegeschwindigkeit zu ändern.",
            "Activez l’« Accès aux notifications » pour cette application afin de modifier la vitesse de lecture.",
            "فعّل «الوصول إلى الإشعارات» لهذا التطبيق لتغيير سرعة التشغيل.");
        put("No playing video or audio found.",
            "No se encontró ningún vídeo ni audio en reproducción.",
            "Es wurde kein laufendes Video oder Audio gefunden.",
            "Aucune vidéo ni aucun audio en lecture n’a été trouvé.",
            "لم يتم العثور على فيديو أو صوت قيد التشغيل.");
        put("This app doesn't allow changing speed from outside.",
            "Esta app no permite cambiar la velocidad desde fuera.",
            "Diese App erlaubt keine Geschwindigkeitsänderung von außen.",
            "Cette application n’autorise pas la modification de la vitesse depuis l’extérieur.",
            "لا يسمح هذا التطبيق بتغيير السرعة من الخارج.");
        put("To change YouTube speed, turn on this app's Accessibility service.",
            "Para cambiar la velocidad de YouTube, activa el servicio de accesibilidad de esta app.",
            "Um die YouTube-Geschwindigkeit zu ändern, aktiviere den Bedienungshilfen-Dienst dieser App.",
            "Pour modifier la vitesse de YouTube, activez le service d’accessibilité de cette application.",
            "لتغيير سرعة يوتيوب، فعّل خدمة إمكانية الوصول الخاصة بهذا التطبيق.");
        put("Setting YouTube speed…",
            "Ajustando la velocidad de YouTube…",
            "YouTube-Geschwindigkeit wird eingestellt …",
            "Réglage de la vitesse de YouTube…",
            "جارٍ ضبط سرعة يوتيوب…");
        put("Failed; the YouTube video must be on screen.",
            "Falló; el vídeo de YouTube debe estar en pantalla.",
            "Fehlgeschlagen; das YouTube-Video muss auf dem Bildschirm sein.",
            "Échec ; la vidéo YouTube doit être affichée à l’écran.",
            "فشلت العملية؛ يجب أن يكون فيديو يوتيوب ظاهرًا على الشاشة.");
        put("Accessibility service isn't connected yet; toggle it off/on once.",
            "El servicio de accesibilidad aún no está conectado; desactívalo y actívalo una vez.",
            "Der Bedienungshilfen-Dienst ist noch nicht verbunden; schalte ihn einmal aus und wieder ein.",
            "Le service d’accessibilité n’est pas encore connecté ; désactivez-le puis réactivez-le une fois.",
            "خدمة إمكانية الوصول غير متصلة بعد؛ أوقفها ثم شغّلها مرة واحدة.");
        put("Font",
            "Fuente",
            "Schrift",
            "Police",
            "الخط");
        put("Font file for {0} not found in assets/fonts",
            "No se encontró el archivo de fuente de {0} en assets/fonts",
            "Schriftdatei für {0} nicht in assets/fonts gefunden",
            "Fichier de police pour {0} introuvable dans assets/fonts",
            "لم يتم العثور على ملف الخط {0} في assets/fonts");
        put("Size, opacity and font reset",
            "Tamaño, opacidad y fuente restablecidos",
            "Größe, Deckkraft und Schrift zurückgesetzt",
            "Taille, opacité et police réinitialisées",
            "تمت إعادة ضبط الحجم والشفافية والخط");
        put("No translation (offline)",
            "Sin traducción (sin conexión)",
            "Keine Übersetzung (offline)",
            "Pas de traduction (hors ligne)",
            "لا توجد ترجمة (دون اتصال)");
        put("Nothing to save yet",
            "Aún no hay nada que guardar",
            "Noch nichts zu speichern",
            "Rien à enregistrer pour le moment",
            "لا يوجد ما يمكن حفظه بعد");
        put("Saved video",
            "Vídeo guardado",
            "Gespeichertes Video",
            "Vidéo enregistrée",
            "فيديو محفوظ");
        put("Unknown source (no link)",
            "Fuente desconocida (sin enlace)",
            "Unbekannte Quelle (kein Link)",
            "Source inconnue (sans lien)",
            "مصدر غير معروف (بلا رابط)");
        put("Saved ✓ (no link). For the link: Share → Copy link in that app, then tap 💾 again",
            "Guardado ✓ (sin enlace). Para el enlace: Compartir → Copiar enlace en esa app y toca 💾 de nuevo",
            "Gespeichert ✓ (kein Link). Für den Link: In dieser App Teilen → Link kopieren, dann erneut auf 💾 tippen",
            "Enregistré ✓ (sans lien). Pour le lien : Partager → Copier le lien dans cette application, puis touchez de nouveau 💾",
            "تم الحفظ ✓ (بلا رابط). للحصول على الرابط: مشاركة ← نسخ الرابط في ذلك التطبيق، ثم اضغط 💾 مرة أخرى");
        put("Live translation",
            "Traducción en vivo",
            "Live-Übersetzung",
            "Traduction en direct",
            "الترجمة الفورية");
        put("Source saved ✓ — see Saved stories in the app",
            "Fuente guardada ✓ — mira «Historias guardadas» en la app",
            "Quelle gespeichert ✓ — siehe „Gespeicherte Geschichten“ in der App",
            "Source enregistrée ✓ — voir « Histoires enregistrées » dans l’application",
            "تم حفظ المصدر ✓ — انظر «القصص المحفوظة» في التطبيق");
        put("Video link saved ✓ — see Saved stories in the app",
            "Enlace del vídeo guardado ✓ — mira «Historias guardadas» en la app",
            "Videolink gespeichert ✓ — siehe „Gespeicherte Geschichten“ in der App",
            "Lien de la vidéo enregistré ✓ — voir « Histoires enregistrées » dans l’application",
            "تم حفظ رابط الفيديو ✓ — انظر «القصص المحفوظة» في التطبيق");
        put("Synced subtitles need “Notification access”; or choose “Full text only”",
            "Los subtítulos sincronizados necesitan «Acceso a notificaciones»; o elige «Solo texto completo»",
            "Synchronisierte Untertitel benötigen den „Benachrichtigungszugriff“; oder wähle „Nur Volltext“",
            "Les sous-titres synchronisés nécessitent l’« Accès aux notifications » ; ou choisissez « Texte intégral uniquement »",
            "تحتاج الترجمات المتزامنة إلى «الوصول إلى الإشعارات»؛ أو اختر «النص الكامل فقط»");
        put("Notification access is not enabled yet",
            "El acceso a notificaciones aún no está activado",
            "Der Benachrichtigungszugriff ist noch nicht aktiviert",
            "L’accès aux notifications n’est pas encore activé",
            "الوصول إلى الإشعارات غير مفعّل بعد");
        put("Fetching the full subtitles…",
            "Obteniendo los subtítulos completos…",
            "Vollständige Untertitel werden geladen …",
            "Récupération des sous-titres complets…",
            "جارٍ جلب الترجمات الكاملة…");
        put("YouTube subtitles off",
            "Subtítulos de YouTube desactivados",
            "YouTube-Untertitel aus",
            "Sous-titres YouTube désactivés",
            "تم إيقاف ترجمات يوتيوب");
        put("The offline pack is still downloading — please wait",
            "El paquete sin conexión aún se está descargando — espera un momento",
            "Das Offline-Paket wird noch heruntergeladen — bitte warten",
            "Le pack hors ligne est encore en cours de téléchargement — veuillez patienter",
            "لا تزال حزمة التشغيل دون اتصال قيد التنزيل — يرجى الانتظار");
        put("For live translation, download the offline pack from the app settings",
            "Para la traducción en vivo, descarga el paquete sin conexión desde los ajustes de la app",
            "Lade für die Live-Übersetzung das Offline-Paket in den App-Einstellungen herunter",
            "Pour la traduction en direct, téléchargez le pack hors ligne depuis les paramètres de l’application",
            "للترجمة الفورية، نزّل حزمة التشغيل دون اتصال من إعدادات التطبيق");
        put("Another download is already running",
            "Ya hay otra descarga en curso",
            "Es läuft bereits ein anderer Download",
            "Un autre téléchargement est déjà en cours",
            "يوجد تنزيل آخر قيد التشغيل بالفعل");
        put("An internet connection is needed to download the pack",
            "Se necesita conexión a internet para descargar el paquete",
            "Zum Herunterladen des Pakets ist eine Internetverbindung nötig",
            "Une connexion Internet est nécessaire pour télécharger le pack",
            "يلزم الاتصال بالإنترنت لتنزيل الحزمة");
        put("Offline pack download started…",
            "Comenzó la descarga del paquete sin conexión…",
            "Download des Offline-Pakets gestartet …",
            "Téléchargement du pack hors ligne démarré…",
            "بدأ تنزيل حزمة التشغيل دون اتصال…");
        put("Downloading offline pack… ",
            "Descargando paquete sin conexión… ",
            "Offline-Paket wird heruntergeladen … ",
            "Téléchargement du pack hors ligne… ",
            "جارٍ تنزيل حزمة التشغيل دون اتصال… ");
        put("Offline pack ready ✓ — tap 🎙 again",
            "Paquete sin conexión listo ✓ — toca 🎙 de nuevo",
            "Offline-Paket bereit ✓ — tippe erneut auf 🎙",
            "Pack hors ligne prêt ✓ — touchez de nouveau 🎙",
            "الحزمة جاهزة ✓ — اضغط 🎙 مرة أخرى");
        put("Download cancelled",
            "Descarga cancelada",
            "Download abgebrochen",
            "Téléchargement annulé",
            "أُلغي التنزيل");
        put("Download failed — check your connection and try again (it resumes)",
            "Falló la descarga — revisa tu conexión e inténtalo de nuevo (se reanuda)",
            "Download fehlgeschlagen — prüfe deine Verbindung und versuche es erneut (er wird fortgesetzt)",
            "Échec du téléchargement — vérifiez votre connexion et réessayez (il reprend)",
            "فشل التنزيل — تحقق من اتصالك وحاول مرة أخرى (سيُستأنف)");
        put("Could not start the offline engine",
            "No se pudo iniciar el motor sin conexión",
            "Die Offline-Engine konnte nicht gestartet werden",
            "Impossible de démarrer le moteur hors ligne",
            "تعذّر تشغيل المحرك دون اتصال");
        put("🎙 Running…",
            "🎙 En ejecución…",
            "🎙 Läuft …",
            "🎙 En cours…",
            "🎙 قيد التشغيل…");
        put("The offline pack failed to load — please download it again",
            "No se pudo cargar el paquete sin conexión — descárgalo de nuevo",
            "Das Offline-Paket konnte nicht geladen werden — bitte erneut herunterladen",
            "Le pack hors ligne n’a pas pu être chargé — veuillez le télécharger à nouveau",
            "تعذّر تحميل حزمة التشغيل دون اتصال — نزّلها مرة أخرى");
        put("No speech recognized — check the source language",
            "No se reconoció voz — revisa el idioma de origen",
            "Keine Sprache erkannt — prüfe die Ausgangssprache",
            "Aucune parole reconnue — vérifiez la langue source",
            "لم يُتعرَّف على كلام — تحقق من لغة المصدر");
        put("Stopped",
            "Grabación detenida",
            "Aufnahme gestoppt",
            "Enregistrement arrêté",
            "توقّف التسجيل");
        put("Google speech unavailable; using server",
            "Reconocimiento de voz de Google no disponible; usando el servidor",
            "Google-Spracherkennung nicht verfügbar; Server wird verwendet",
            "Reconnaissance vocale Google indisponible ; utilisation du serveur",
            "التعرّف على الكلام من Google غير متاح؛ يتم استخدام الخادم");
        put("Microphone permission required",
            "Se necesita permiso del micrófono",
            "Mikrofonberechtigung erforderlich",
            "Autorisation du microphone requise",
            "مطلوب إذن الميكروفون");
        put("Overlay permission is not granted",
            "No se ha concedido el permiso de superposición",
            "Die Überlagerungsberechtigung wurde nicht erteilt",
            "L’autorisation d’affichage par-dessus les autres applications n’est pas accordée",
            "لم يتم منح إذن العرض فوق التطبيقات الأخرى");
        put("Drag the magnifier over a text and release",
            "Arrastra la lupa sobre un texto y suelta",
            "Ziehe die Lupe über einen Text und lass los",
            "Faites glisser la loupe sur un texte puis relâchez",
            "اسحب العدسة المكبّرة فوق نص ثم أفلتها");
        put("Close",
            "Cerrar",
            "Schließen",
            "Fermer",
            "إغلاق");
        put("Magnifier is active",
            "La lupa está activa",
            "Die Lupe ist aktiv",
            "La loupe est active",
            "العدسة المكبّرة نشطة");
        put("Magnifier closed",
            "Lupa cerrada",
            "Lupe geschlossen",
            "Loupe fermée",
            "أُغلقت العدسة المكبّرة");
        put("No screen image yet, try again",
            "Aún no hay imagen de la pantalla, inténtalo de nuevo",
            "Noch kein Bildschirmbild, versuche es erneut",
            "Pas encore d’image de l’écran, réessayez",
            "لا توجد صورة للشاشة بعد، حاول مرة أخرى");
        put("Reading text from the screen is not supported for this language yet",
            "La lectura de texto de la pantalla aún no es compatible con este idioma",
            "Das Lesen von Text vom Bildschirm wird für diese Sprache noch nicht unterstützt",
            "La lecture du texte à l’écran n’est pas encore prise en charge pour cette langue",
            "قراءة النص من الشاشة غير مدعومة لهذه اللغة بعد");
        put("Could not read the text",
            "No se pudo leer el texto",
            "Der Text konnte nicht gelesen werden",
            "Impossible de lire le texte",
            "تعذّرت قراءة النص");
        put("No text found under the magnifier",
            "No se encontró texto bajo la lupa",
            "Unter der Lupe wurde kein Text gefunden",
            "Aucun texte trouvé sous la loupe",
            "لم يُعثر على نص تحت العدسة المكبّرة");
        put("No text found",
            "No se encontró texto",
            "Kein Text gefunden",
            "Aucun texte trouvé",
            "لم يُعثر على نص");
        put("Tap a word, or drag across several words in the highlighted text",
            "Toca una palabra o arrastra sobre varias palabras del texto resaltado",
            "Tippe auf ein Wort oder ziehe über mehrere Wörter im markierten Text",
            "Touchez un mot ou faites glisser sur plusieurs mots du texte surligné",
            "اضغط على كلمة أو اسحب فوق عدة كلمات في النص المظلَّل");
        put("Repeat ∞ — until you press ⏹",
            "Repetir ∞ — hasta que pulses ⏹",
            "Wiederholen ∞ — bis du ⏹ drückst",
            "Répéter ∞ — jusqu’à ce que vous appuyiez sur ⏹",
            "تكرار ∞ — حتى تضغط ⏹");
        put("Each reading {0}×",
            "Cada lectura {0}×",
            "Jedes Vorlesen {0}×",
            "Chaque lecture {0}×",
            "كل قراءة {0} مرة");
        put("Couldn't identify the video: ",
            "No pude identificar el vídeo: ",
            "Das Video konnte nicht erkannt werden: ",
            "Impossible d’identifier la vidéo : ",
            "تعذّر التعرّف على الفيديو: ");
        put("This video has no captions",
            "Este vídeo no tiene subtítulos",
            "Dieses Video hat keine Untertitel",
            "Cette vidéo n’a pas de sous-titres",
            "ليس لهذا الفيديو ترجمات");
        put("Captions failed: ",
            "Error en los subtítulos: ",
            "Untertitel fehlgeschlagen: ",
            "Échec des sous-titres : ",
            "تعذّر جلب الترجمات: ");
        put("Audio capture permission denied",
            "Permiso de captura de audio denegado",
            "Berechtigung zur Audioaufnahme verweigert",
            "Autorisation de capture audio refusée",
            "تم رفض إذن التقاط الصوت");
        put("Tap: start/stop  ·  Hold: history  ·  ✕ or drag down: close",
            "Toca: iniciar/detener  ·  Mantén: historial  ·  ✕ o arrastra hacia abajo: cerrar",
            "Tippen: Start/Stopp  ·  Halten: Verlauf  ·  ✕ oder nach unten ziehen: schließen",
            "Toucher : démarrer/arrêter  ·  Maintenir : historique  ·  ✕ ou glisser vers le bas : fermer",
            "اضغط: بدء/إيقاف  ·  اضغط مطولًا: السجل  ·  ✕ أو اسحب للأسفل: إغلاق");
        put("about {0} MB",
            "unos {0} MB",
            "etwa {0} MB",
            "environ {0} Mo",
            "حوالي {0} ميغابايت");
        put("tens to a few hundred MB",
            "de decenas a unos cientos de MB",
            "einige Dutzend bis einige hundert MB",
            "de quelques dizaines à quelques centaines de Mo",
            "من عشرات إلى بضع مئات من الميغابايت");
        put("Download offline speech pack",
            "Descargar paquete de reconocimiento de voz sin conexión",
            "Offline-Spracherkennungspaket herunterladen",
            "Télécharger le pack de reconnaissance vocale hors ligne",
            "تنزيل حزمة التعرّف على الكلام دون اتصال");
        put("Download",
            "Descargar",
            "Herunterladen",
            "Télécharger",
            "تنزيل");
        put("Not now",
            "Ahora no",
            "Jetzt nicht",
            "Pas maintenant",
            "ليس الآن");
        put("Live translation uses offline speech recognition: processing happens entirely on your phone, no audio is sent to any server, and the screen stays smooth.",
            "La traducción en vivo usa reconocimiento de voz sin conexión: todo se procesa en tu teléfono, no se envía audio a ningún servidor y la pantalla sigue fluida.",
            "Die Live-Übersetzung nutzt Offline-Spracherkennung: Alles wird komplett auf deinem Handy verarbeitet, es wird kein Audio an einen Server gesendet, und die Anzeige bleibt flüssig.",
            "La traduction en direct utilise la reconnaissance vocale hors ligne : le traitement se fait entièrement sur votre téléphone, aucun son n’est envoyé à un serveur et l’écran reste fluide.",
            "تعتمد الترجمة الفورية على التعرّف على الكلام دون اتصال: تتم المعالجة بالكامل على هاتفك، ولا يُرسل أي صوت إلى أي خادم، وتبقى الشاشة سلسة.");
        put("This language (or “Auto”) needs the multilingual Whisper pack. It is slower than the per-language packs, so translations may appear a few seconds late.",
            "Este idioma (o «Automático») necesita el paquete multilingüe Whisper. Es más lento que los paquetes por idioma, así que las traducciones pueden aparecer con unos segundos de retraso.",
            "Diese Sprache (oder „Automatisch“) benötigt das mehrsprachige Whisper-Paket. Es ist langsamer als die sprachspezifischen Pakete, daher können Übersetzungen einige Sekunden verspätet erscheinen.",
            "Cette langue (ou « Auto ») nécessite le pack multilingue Whisper. Il est plus lent que les packs propres à chaque langue ; les traductions peuvent donc apparaître avec quelques secondes de retard.",
            "تحتاج هذه اللغة (أو «تلقائي») إلى حزمة Whisper متعددة اللغات. وهي أبطأ من الحزم الخاصة بكل لغة، لذا قد تظهر الترجمات متأخرة بضع ثوانٍ.");
        put("A one-time offline pack is required for “{0}”.",
            "Se necesita descargar una sola vez un paquete sin conexión para «{0}».",
            "Für „{0}“ ist einmalig ein Offline-Paket erforderlich.",
            "Un pack hors ligne doit être téléchargé une seule fois pour « {0} ».",
            "يلزم تنزيل حزمة دون اتصال مرة واحدة للغة «{0}».");
        put("Pack size: {0}. We recommend Wi‑Fi. An interrupted download resumes where it stopped.",
            "Tamaño del paquete: {0}. Recomendamos usar Wi‑Fi. Si la descarga se interrumpe, se reanuda donde se detuvo.",
            "Paketgröße: {0}. Wir empfehlen WLAN. Ein unterbrochener Download wird dort fortgesetzt, wo er gestoppt hat.",
            "Taille du pack : {0}. Nous recommandons le Wi‑Fi. Un téléchargement interrompu reprend là où il s’est arrêté.",
            "حجم الحزمة: {0}. يُنصح بالاتصال عبر Wi‑Fi. إذا انقطع التنزيل فسيُستأنف من حيث توقف.");
        put("Without this pack, live translation will not work. Download it now?",
            "Sin este paquete, la traducción en vivo no funcionará. ¿Descargarlo ahora?",
            "Ohne dieses Paket funktioniert die Live-Übersetzung nicht. Jetzt herunterladen?",
            "Sans ce pack, la traduction en direct ne fonctionnera pas. Le télécharger maintenant ?",
            "بدون هذه الحزمة لن تعمل الترجمة الفورية. هل تريد تنزيلها الآن؟");
        put("YouTube subtitles",
            "Subtítulos de YouTube",
            "YouTube-Untertitel",
            "Sous-titres YouTube",
            "ترجمات يوتيوب");
        put("Full text only",
            "Solo texto completo",
            "Nur Volltext",
            "Texte intégral uniquement",
            "النص الكامل فقط");
        put("Synced",
            "Sincronizado",
            "Synchronisiert",
            "Synchronisé",
            "متزامن");
        put("1) Synced: highlights the sentence being played; ⏮ and ↺ work. Needs “Notification access”. Only YouTube's own notification is checked (to detect the video and its play time); other apps' notifications are not read or stored. You can turn it off anytime in Android settings.",
            "1) Sincronizado: resalta la frase que se está reproduciendo; ⏮ y ↺ funcionan. Requiere «Acceso a notificaciones». Solo se comprueba la notificación de YouTube (para detectar el vídeo y su tiempo de reproducción); las notificaciones de otras apps no se leen ni se guardan. Puedes desactivarlo cuando quieras en los ajustes de Android.",
            "1) Synchronisiert: Der gerade abgespielte Satz wird hervorgehoben; ⏮ und ↺ funktionieren. Erfordert den „Benachrichtigungszugriff“. Es wird nur die Benachrichtigung von YouTube selbst geprüft (um das Video und die Wiedergabezeit zu erkennen); Benachrichtigungen anderer Apps werden weder gelesen noch gespeichert. Du kannst es jederzeit in den Android-Einstellungen ausschalten.",
            "1) Synchronisé : la phrase en cours de lecture est mise en évidence ; ⏮ et ↺ fonctionnent. Nécessite l’« Accès aux notifications ». Seule la notification de YouTube est vérifiée (pour détecter la vidéo et son temps de lecture) ; les notifications des autres applications ne sont ni lues ni enregistrées. Vous pouvez le désactiver à tout moment dans les paramètres d’Android.",
            "١) متزامن: تُمَيَّز الجملة التي تُشغَّل حاليًا، ويعمل زرّا ⏮ و↺. يتطلب «الوصول إلى الإشعارات». يُفحص إشعار يوتيوب نفسه فقط (للتعرّف على الفيديو ووقت التشغيل)، ولا تُقرأ إشعارات التطبيقات الأخرى ولا تُحفظ. يمكنك إيقافه في أي وقت من إعدادات أندرويد.");
        put("2) Full text only: no permission. Copy the video link in YouTube (Share → Copy link), then tap “Full text only”; all subtitles load at once, with no highlighting, plus a repeat-all-text button.",
            "2) Solo texto completo: sin permisos. Copia el enlace del vídeo en YouTube (Compartir → Copiar enlace) y toca «Solo texto completo»; todos los subtítulos se cargan de una vez, sin resaltado, y con un botón para repetir todo el texto.",
            "2) Nur Volltext: keine Berechtigung nötig. Kopiere den Videolink in YouTube (Teilen → Link kopieren) und tippe dann auf „Nur Volltext“; alle Untertitel werden auf einmal geladen, ohne Hervorhebung, plus eine Schaltfläche zum Wiederholen des gesamten Textes.",
            "2) Texte intégral uniquement : aucune autorisation. Copiez le lien de la vidéo dans YouTube (Partager → Copier le lien), puis touchez « Texte intégral uniquement » ; tous les sous-titres se chargent d’un coup, sans surbrillance, avec un bouton pour répéter tout le texte.",
            "٢) النص الكامل فقط: بلا أي إذن. انسخ رابط الفيديو في يوتيوب (مشاركة ← نسخ الرابط) ثم اضغط «النص الكامل فقط»؛ تُحمَّل كل الترجمات دفعة واحدة، بلا تمييز، مع زر لتكرار النص كاملًا.");
    }
}
