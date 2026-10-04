package com.linglearn.app.overlay;

/** هر موتور تشخیص گفتارِ آفلاین (Sherpa جریانی یا Whisper) این رو پیاده می‌کنه تا feedLoop بشناسدش. */
interface PcmSink {
    /** PCM 16kHz mono 16-bit little-endian. نباید بلاک کنه. */
    void accept(byte[] pcm16k, int len);
    void release();
}
