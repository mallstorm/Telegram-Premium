package org.telegram.messenger.telepremium.media;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Stereo balance and mono mix-down for 16-bit PCM stereo. Gains are read on every buffer,
 * so changes from the sound settings apply live without re-preparing the player.
 * Other formats pass through untouched.
 */
public final class TelePremiumBalanceAudioProcessor extends BaseAudioProcessor {
    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioFormat.NOT_SET;
        }
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) return;
        ByteBuffer out = replaceOutputBuffer(remaining);
        float left = TelePremiumAudioEffects.leftGain();
        float right = TelePremiumAudioEffects.rightGain();
        boolean mono = TelePremiumAudioEffects.isMono();
        ByteBuffer in = inputBuffer.order(ByteOrder.nativeOrder());
        while (in.remaining() >= 4) {
            int l = in.getShort();
            int r = in.getShort();
            if (mono) {
                int m = (l + r) / 2;
                l = m;
                r = m;
            }
            out.putShort(clip(l * left));
            out.putShort(clip(r * right));
        }
        while (in.hasRemaining()) out.put(in.get());
        out.flip();
    }

    private static short clip(float v) {
        if (v > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (v < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) v;
    }

    /** Convenience for VideoPlayer.extraAudioProcessors. */
    public static AudioProcessor[] chain() {
        return new AudioProcessor[]{new TelePremiumBalanceAudioProcessor()};
    }
}
