package org.telegram.messenger.telepremium.media;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * TelePremium sound engine: a PCM DSP running inside the player (16-bit PCM, any channel
 * count, any sample rate). Chain per channel: preamp → bass shelf (100 Hz) → 10 peaking
 * bands (31 Hz … 16 kHz, one octave, Q≈1.41) → treble shelf (8 kHz); then stereo width,
 * mono, balance and a soft limiter that prevents clipping when bands are boosted.
 * Settings are re-read when {@link TelePremiumAudioEffects#version()} changes, keeping the
 * filter state, so moving a slider is heard immediately and without clicks.
 */
@OptIn(markerClass = UnstableApi.class)
public final class TelePremiumBalanceAudioProcessor extends BaseAudioProcessor {
    private static final int MAX_FILTERS = TelePremiumAudioEffects.BANDS + 2;

    private int channels;
    private int sampleRate;
    private int appliedVersion = -1;

    // Normalized biquad coefficients per filter (b0, b1, b2, a1, a2) and the active flags.
    private final float[][] coef = new float[MAX_FILTERS][5];
    private final boolean[] active = new boolean[MAX_FILTERS];
    private int activeCount;
    // Transposed direct form II state: [channel][filter][2].
    private float[][][] state = new float[2][MAX_FILTERS][2];

    private float preamp = 1f;
    private boolean dspOn;
    private float width = 1f, leftGain = 1f, rightGain = 1f;
    private boolean mono, limiter = true;

    @Override
    protected AudioFormat onConfigure(AudioFormat input) throws UnhandledAudioFormatException {
        if (input.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET;
        channels = input.channelCount;
        sampleRate = input.sampleRate;
        state = new float[Math.max(1, channels)][MAX_FILTERS][2];
        appliedVersion = -1;
        return input;
    }

    @Override
    protected void onFlush() {
        for (float[][] ch : state) for (float[] f : ch) { f[0] = 0; f[1] = 0; }
    }

    private void refresh() {
        int v = TelePremiumAudioEffects.version();
        if (v == appliedVersion) return;
        appliedVersion = v;
        boolean eq = TelePremiumAudioEffects.isEqEnabled();
        float[] bands = TelePremiumAudioEffects.getBands();
        activeCount = 0;
        float nyquist = sampleRate / 2f;
        // 0: bass shelf, 1..10: bands, 11: treble shelf
        float bass = TelePremiumAudioEffects.getBass();
        active[0] = Math.abs(bass) > 0.05f;
        if (active[0]) shelf(coef[0], true, 100f, bass);
        for (int i = 0; i < TelePremiumAudioEffects.BANDS; i++) {
            float f = TelePremiumAudioEffects.FREQUENCIES[i];
            boolean on = eq && Math.abs(bands[i]) > 0.05f && f < nyquist * 0.9f;
            active[i + 1] = on;
            if (on) peaking(coef[i + 1], f, 1.41f, bands[i]);
        }
        float treble = TelePremiumAudioEffects.getTreble();
        active[MAX_FILTERS - 1] = Math.abs(treble) > 0.05f && 8000f < nyquist * 0.9f;
        if (active[MAX_FILTERS - 1]) shelf(coef[MAX_FILTERS - 1], false, 8000f, treble);
        for (boolean a : active) if (a) activeCount++;
        preamp = eq ? (float) Math.pow(10, TelePremiumAudioEffects.getPreamp() / 20f) : 1f;
        width = TelePremiumAudioEffects.getWidth() / 100f;
        int balance = TelePremiumAudioEffects.getBalance();
        leftGain = balance > 0 ? (100 - balance) / 100f : 1f;
        rightGain = balance < 0 ? (100 + balance) / 100f : 1f;
        mono = TelePremiumAudioEffects.getMono();
        limiter = TelePremiumAudioEffects.isLimiter();
        dspOn = activeCount > 0 || preamp != 1f || width != 1f || leftGain != 1f || rightGain != 1f || mono;
    }

    /** RBJ cookbook peaking EQ. */
    private void peaking(float[] c, float f, float q, float db) {
        double a = Math.pow(10, db / 40.0);
        double w0 = 2 * Math.PI * f / sampleRate;
        double alpha = Math.sin(w0) / (2 * q);
        double cos = Math.cos(w0);
        double a0 = 1 + alpha / a;
        c[0] = (float) ((1 + alpha * a) / a0);
        c[1] = (float) ((-2 * cos) / a0);
        c[2] = (float) ((1 - alpha * a) / a0);
        c[3] = (float) ((-2 * cos) / a0);
        c[4] = (float) ((1 - alpha / a) / a0);
    }

    /** RBJ cookbook low / high shelf, slope S = 1. */
    private void shelf(float[] c, boolean low, float f, float db) {
        double a = Math.pow(10, db / 40.0);
        double w0 = 2 * Math.PI * f / sampleRate;
        double cos = Math.cos(w0), sin = Math.sin(w0);
        double alpha = sin / 2 * Math.sqrt(2);
        double sq = 2 * Math.sqrt(a) * alpha;
        double b0, b1, b2, a0, a1, a2;
        if (low) {
            b0 = a * ((a + 1) - (a - 1) * cos + sq);
            b1 = 2 * a * ((a - 1) - (a + 1) * cos);
            b2 = a * ((a + 1) - (a - 1) * cos - sq);
            a0 = (a + 1) + (a - 1) * cos + sq;
            a1 = -2 * ((a - 1) + (a + 1) * cos);
            a2 = (a + 1) + (a - 1) * cos - sq;
        } else {
            b0 = a * ((a + 1) + (a - 1) * cos + sq);
            b1 = -2 * a * ((a - 1) + (a + 1) * cos);
            b2 = a * ((a + 1) + (a - 1) * cos - sq);
            a0 = (a + 1) - (a - 1) * cos + sq;
            a1 = 2 * ((a - 1) - (a + 1) * cos);
            a2 = (a + 1) - (a - 1) * cos - sq;
        }
        c[0] = (float) (b0 / a0);
        c[1] = (float) (b1 / a0);
        c[2] = (float) (b2 / a0);
        c[3] = (float) (a1 / a0);
        c[4] = (float) (a2 / a0);
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) return;
        refresh();
        ByteBuffer out = replaceOutputBuffer(remaining);
        ByteBuffer in = inputBuffer.order(ByteOrder.nativeOrder());
        if (!dspOn || channels <= 0) {
            out.put(in);
            out.flip();
            TelePremiumStreamRecorder.feed(out, sampleRate, channels);
            return;
        }
        int frameBytes = channels * 2;
        float[] frame = new float[channels];
        while (in.remaining() >= frameBytes) {
            for (int ch = 0; ch < channels; ch++) {
                float x = in.getShort() / 32768f * preamp;
                if (activeCount > 0) {
                    float[][] st = state[ch];
                    for (int f = 0; f < MAX_FILTERS; f++) {
                        if (!active[f]) continue;
                        float[] c = coef[f];
                        float[] s = st[f];
                        float y = c[0] * x + s[0];
                        s[0] = c[1] * x - c[3] * y + s[1];
                        s[1] = c[2] * x - c[4] * y;
                        x = y;
                    }
                }
                frame[ch] = x;
            }
            if (channels == 2) {
                float l = frame[0], r = frame[1];
                if (mono) {
                    float m = (l + r) * 0.5f;
                    l = m;
                    r = m;
                } else if (width != 1f) {
                    float m = (l + r) * 0.5f, s = (l - r) * 0.5f * width;
                    l = m + s;
                    r = m - s;
                }
                frame[0] = l * leftGain;
                frame[1] = r * rightGain;
            }
            for (int ch = 0; ch < channels; ch++) out.putShort(toPcm(frame[ch]));
        }
        while (in.hasRemaining()) out.put(in.get());
        out.flip();
        TelePremiumStreamRecorder.feed(out, sampleRate, channels);
    }

    private short toPcm(float x) {
        if (limiter) {
            // Soft knee above -2 dBFS: boosts never hard-clip into distortion.
            float ax = Math.abs(x);
            if (ax > 0.8f) {
                float over = (ax - 0.8f) / 0.2f;
                ax = 0.8f + 0.2f * (float) Math.tanh(over);
                x = x < 0 ? -ax : ax;
            }
        }
        int v = Math.round(x * 32767f);
        if (v > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (v < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) v;
    }

    /** For VideoPlayer.extraAudioProcessors. */
    public static AudioProcessor[] chain() {
        return new AudioProcessor[]{new TelePremiumBalanceAudioProcessor()};
    }
}
