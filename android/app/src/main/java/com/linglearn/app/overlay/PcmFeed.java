package com.linglearn.app.overlay;

import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;

/**
 * پلِ بینِ BubbleService و SpeechRecognizer:
 * BubbleService صدای ضبط‌شده‌ی سیستم (PCM 16kHz mono 16-bit) رو با write() می‌ریزه توی صف،
 * و یک thread جدا اون رو توی یک pipe می‌نویسه. سرِ دیگه‌ی pipe با EXTRA_AUDIO_SOURCE
 * به SpeechRecognizer داده می‌شه (SpeechHostActivity.newSession()).
 *
 * نکته: write() هیچ‌وقت بلاک نمی‌شه (صفِ محدود؛ قدیمی‌ترین تکه‌ها دور ریخته می‌شن).
 */
final class PcmFeed {

    private static final int MAX_CHUNKS = 100; // حدود ۲ ثانیه (هر تکه ~۲۰ms)

    private static final Object LOCK = new Object();
    private static final ArrayDeque<byte[]> QUEUE = new ArrayDeque<>();
    private static OutputStream sink;          // سرِ نوشتنِ pipe ی فعلی
    private static Thread writer;
    private static boolean stop = false;

    private PcmFeed() {}

    /** صدای جدید رو توی صف می‌ذاره (بدونِ بلاک). */
    static void write(byte[] buf, int len) {
        if (buf == null || len <= 0) return;
        byte[] copy = new byte[len];
        System.arraycopy(buf, 0, copy, 0, len);
        synchronized (LOCK) {
            while (QUEUE.size() >= MAX_CHUNKS) QUEUE.pollFirst();
            QUEUE.addLast(copy);
            LOCK.notifyAll();
        }
    }

    /**
     * یک pipe ی تازه می‌سازه و سرِ خواندنش رو برمی‌گردونه (برای EXTRA_AUDIO_SOURCE).
     * pipe ی قبلی بسته می‌شه. صدایی که توی صف مونده به pipe ی جدید می‌ره.
     */
    static ParcelFileDescriptor newSession() throws IOException {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        synchronized (LOCK) {
            closeSinkLocked();
            sink = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
            stop = false;
            if (writer == null || !writer.isAlive()) {
                writer = new Thread(PcmFeed::runWriter, "pcm-feed-writer");
                writer.setDaemon(true);
                writer.start();
            }
            LOCK.notifyAll();
        }
        return pipe[0];
    }

    /** همه‌چیز رو می‌بنده و صف رو خالی می‌کنه. */
    static void close() {
        synchronized (LOCK) {
            stop = true;
            QUEUE.clear();
            closeSinkLocked();
            LOCK.notifyAll();
        }
    }

    private static void closeSinkLocked() {
        OutputStream s = sink;
        sink = null;
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private static void runWriter() {
        while (true) {
            byte[] chunk;
            OutputStream out;
            synchronized (LOCK) {
                while (!stop && (sink == null || QUEUE.isEmpty())) {
                    try {
                        LOCK.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (stop) {
                    writer = null;
                    return;
                }
                chunk = QUEUE.pollFirst();
                out = sink;
            }
            if (chunk == null || out == null) continue;
            try {
                out.write(chunk);
            } catch (Throwable e) {
                // سرِ خواندنِ pipe بسته شده (recognizer تموم کرده) → تا session ی بعدی دور می‌ریزیم
                synchronized (LOCK) {
                    if (sink == out) {
                        try { out.close(); } catch (Throwable ignored) {}
                        sink = null;
                    }
                }
            }
        }
    }
}
