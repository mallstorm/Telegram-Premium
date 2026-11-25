package org.telegram.messenger.telepremium;

import android.content.Intent;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;

/**
 * Local TelePremium transcription bridge for downloaded Telegram voice/audio messages. Android 13+
 * can feed an already-opened PCM audio source directly into the system RecognitionService via
 * RecognizerIntent.EXTRA_AUDIO_SOURCE.
 *
 * <p>This deliberately does not touch Telegram Premium/account state and does not call
 * messages.transcribeAudio.
 */
public final class TelePremiumLocalTranscriber {

  public interface Callback {
    void onSuccess(String text);

    void onError(String reason);
  }

  private static final long CODEC_TIMEOUT_US = 10_000L;
  private static final int VIDEO_SEGMENT_SECONDS = 35;
  private static final int VIDEO_MAX_SEGMENTS = 60;
  private static final long MAX_PCM_BYTES = 360L * 1024L * 1024L;

  private TelePremiumLocalTranscriber() {}

  public static boolean isSupported() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        && SpeechRecognizer.isRecognitionAvailable(ApplicationLoader.applicationContext);
  }

  public static void transcribe(MessageObject messageObject, Callback callback) {
    if (messageObject == null || messageObject.messageOwner == null) {
      postError(callback, "TelePremium: голосовое сообщение недоступно");
      return;
    }
    if (!isSupported()) {
      postError(
          callback,
          "TelePremium: локальная расшифровка требует Android 13+ и системный Speech Recognizer");
      return;
    }

    final File source =
        FileLoader.getInstance(messageObject.currentAccount)
            .getPathToMessage(messageObject.messageOwner);
    if (source == null || !source.exists() || source.length() <= 0) {
      postError(callback, "TelePremium: сначала загрузите голосовое сообщение");
      return;
    }

    Utilities.globalQueue.postRunnable(
        () -> {
          try {
            DecodedAudio decoded =
                decodeToPcm(source, messageObject.currentAccount, messageObject.getId());
            AndroidUtilities.runOnUIThread(
                () -> {
                  try {
                    new RecognitionSession(decoded, callback).start();
                  } catch (Throwable e) {
                    FileLog.e(e);
                    decoded.file.delete();
                    postError(callback, "TelePremium: не удалось запустить распознавание");
                  }
                });
          } catch (Throwable e) {
            FileLog.e(e);
            postError(callback, "TelePremium: не удалось декодировать голосовое сообщение");
          }
        });
  }

  /**
   * Explicit local-video speech recognition; requires on-device Android recognizer. Audio is
   * decoded to temporary PCM, split into bounded segments and each segment transcribed
   * sequentially. The timestamps mark segment starts, not word alignment. No audio samples are sent
   * to the selected LLM.
   */
  public static void transcribeMediaFile(
      File source, int account, int messageId, Callback callback) {
    transcribeMediaFile(source, account, messageId, new AtomicBoolean(false), callback);
  }

  /** Cancellation is observed between frames/segments and during the decode loop. */
  public static void transcribeMediaFile(
      File source, int account, int messageId, AtomicBoolean cancellation, Callback callback) {
    if (source == null || !source.isFile() || source.length() == 0) {
      postError(callback, "Сначала загрузите видео в Telegram");
      return;
    }
    if (source.length() > 400L * 1024L * 1024L) {
      postError(callback, "Видео слишком большое для локальной транскрипции (лимит 400 МБ)");
      return;
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        || !SpeechRecognizer.isOnDeviceRecognitionAvailable(ApplicationLoader.applicationContext)) {
      postError(
          callback, "Нет локального распознавания речи Android. Сводка возможна только по кадрам.");
      return;
    }
    Utilities.globalQueue.postRunnable(
        () -> {
          if (cancellation.get()) return;
          DecodedAudio pcm = null;
          try {
            pcm = decodeToPcm(source, account, messageId, cancellation);
            if (pcm.file.length() > MAX_PCM_BYTES) {
              pcm.file.delete();
              postError(callback, "Недостаточно ресурсов для расшифровки длинного видео");
              return;
            }
            final DecodedAudio decoded = pcm;
            AndroidUtilities.runOnUIThread(
                () ->
                    transcribeSegments(decoded, 0, new StringBuilder(), 0, cancellation, callback));
          } catch (Throwable failure) {
            if (pcm != null) pcm.file.delete();
            if (cancellation.get()) return;
            FileLog.e(failure);
            postError(callback, "Не удалось декодировать аудиодорожку видео");
          }
        });
  }

  /** One bounded offline segment, used by the persistent AI Center task queue. */
  public static void transcribeSegment(
      File source,
      int account,
      int message,
      long start,
      long end,
      String language,
      AtomicBoolean cancellation,
      Callback callback) {
    if (Build.VERSION.SDK_INT < 33
        || !SpeechRecognizer.isOnDeviceRecognitionAvailable(ApplicationLoader.applicationContext)) {
      postError(
          callback, org.telegram.messenger.telepremium.aicenter.CenterText.get("offlineSpeech"));
      return;
    }
    Utilities.globalQueue.postRunnable(
        () -> {
          try {
            DecodedAudio audio =
                decodeRange(source, account, message, cancellation, start * 1000, end * 1000);
            AndroidUtilities.runOnUIThread(
                () -> {
                  if (cancellation.get()) {
                    audio.file.delete();
                    return;
                  }
                  RecognitionSession session = new RecognitionSession(audio, callback, true);
                  session.language = language;
                  session.cancellation = cancellation;
                  try {
                    session.start();
                  } catch (Exception e) {
                    audio.file.delete();
                    postError(callback, e.getClass().getSimpleName());
                  }
                });
          } catch (Exception e) {
            postError(callback, e.getClass().getSimpleName());
          }
        });
  }

  /** Bounded WAV upload prepared only after explicit per-job cloud-ASR consent. */
  public static File waveSegment(
      File source, int account, int message, long start, long end, AtomicBoolean cancellation)
      throws Exception {
    DecodedAudio pcm =
        decodeRange(source, account, message, cancellation, start * 1000, end * 1000);
    File wave =
        new File(
            ApplicationLoader.applicationContext.getCacheDir(),
            "aic_asr_" + System.nanoTime() + ".wav");
    try {
      int bytes = sampleBytes(pcm.encoding);
      long size = pcm.file.length();
      if (bytes == 0 || size > 24L * 1024 * 1024)
        throw new java.io.IOException("Audio segment size");
      ByteBuffer h = ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      h.put(new byte[] {'R', 'I', 'F', 'F'})
          .putInt((int) size + 36)
          .put(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '})
          .putInt(16);
      h.putShort((short) (pcm.encoding == AudioFormat.ENCODING_PCM_FLOAT ? 3 : 1))
          .putShort((short) pcm.channelCount);
      h.putInt(pcm.sampleRate)
          .putInt(pcm.sampleRate * pcm.channelCount * bytes)
          .putShort((short) (pcm.channelCount * bytes))
          .putShort((short) (bytes * 8));
      h.put(new byte[] {'d', 'a', 't', 'a'}).putInt((int) size);
      try (java.io.InputStream in = new java.io.FileInputStream(pcm.file);
          FileOutputStream out = new FileOutputStream(wave)) {
        out.write(h.array());
        byte[] buffer = new byte[65536];
        for (int n; (n = in.read(buffer)) >= 0; ) {
          if (cancellation.get()) throw new InterruptedException();
          out.write(buffer, 0, n);
        }
      }
      return wave;
    } catch (Exception e) {
      wave.delete();
      throw e;
    } finally {
      pcm.file.delete();
    }
  }

  private static int sampleBytes(int encoding) {
    if (encoding == AudioFormat.ENCODING_PCM_8BIT) return 1;
    if (encoding == AudioFormat.ENCODING_PCM_16BIT) return 2;
    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) return 4;
    return 0;
  }

  private static void transcribeSegments(
      DecodedAudio full,
      int index,
      StringBuilder result,
      int successes,
      AtomicBoolean cancellation,
      Callback callback) {
    if (cancellation.get()) {
      full.file.delete();
      return;
    }
    int width = sampleBytes(full.encoding);
    if (width == 0 || full.sampleRate <= 0 || full.channelCount <= 0) {
      full.file.delete();
      postError(callback, "Формат декодированного аудио не поддерживается распознавателем");
      return;
    }
    long frameSize = (long) width * full.channelCount;
    long bytesPerSegment = (long) VIDEO_SEGMENT_SECONDS * full.sampleRate * frameSize;
    int count =
        (int)
            Math.min(
                VIDEO_MAX_SEGMENTS, (full.file.length() + bytesPerSegment - 1L) / bytesPerSegment);
    if (index >= count) {
      boolean truncated = full.file.length() > (long) count * bytesPerSegment;
      full.file.delete();
      if (successes == 0) {
        postError(callback, "Распознавание речи не вернуло текста");
        return;
      }
      if (truncated) result.append("\n[Остальная аудиодорожка не обработана: достигнут лимит]");
      if (callback != null) callback.onSuccess(result.toString().trim());
      return;
    }

    File segment =
        new File(
            ApplicationLoader.applicationContext.getCacheDir(),
            "tp_ai_segment_" + System.nanoTime() + ".pcm");
    Utilities.globalQueue.postRunnable(
        () -> {
          if (cancellation.get()) {
            full.file.delete();
            return;
          }
          try (RandomAccessFile input = new RandomAccessFile(full.file, "r");
              FileOutputStream output = new FileOutputStream(segment)) {
            long start = index * bytesPerSegment;
            input.seek(start);
            long remaining = Math.min(bytesPerSegment, input.length() - start);
            byte[] buffer = new byte[32768];
            while (remaining > 0) {
              int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
              if (read < 0) break;
              output.write(buffer, 0, read);
              remaining -= read;
            }
          } catch (Exception failure) {
            FileLog.e(failure);
            segment.delete();
            full.file.delete();
            postError(callback, "Не удалось прочитать сегмент видеозаписи");
            return;
          }
          AndroidUtilities.runOnUIThread(
              () -> {
                if (cancellation.get()) {
                  segment.delete();
                  full.file.delete();
                  return;
                }
                int seconds = index * VIDEO_SEGMENT_SECONDS;
                String stamp =
                    String.format(Locale.ROOT, "[%02d:%02d] ", seconds / 60, seconds % 60);
                Callback next =
                    new Callback() {
                      @Override
                      public void onSuccess(String text) {
                        if (!TextUtils.isEmpty(text)) {
                          result.append(stamp).append(text.trim()).append('\n');
                          transcribeSegments(
                              full, index + 1, result, successes + 1, cancellation, callback);
                        } else {
                          transcribeSegments(
                              full, index + 1, result, successes, cancellation, callback);
                        }
                      }

                      @Override
                      public void onError(String reason) {
                        result.append(stamp).append("[речь не распознана]\n");
                        transcribeSegments(
                            full, index + 1, result, successes, cancellation, callback);
                      }
                    };
                try {
                  new RecognitionSession(
                          new DecodedAudio(
                              segment, full.sampleRate, full.channelCount, full.encoding),
                          next,
                          true)
                      .start();
                } catch (Exception failure) {
                  segment.delete();
                  next.onError(failure.getMessage());
                }
              });
        });
  }

  private static void postError(Callback callback, String reason) {
    if (callback == null) {
      return;
    }
    AndroidUtilities.runOnUIThread(() -> callback.onError(reason));
  }

  private static DecodedAudio decodeToPcm(File source, int account, int messageId)
      throws Exception {
    return decodeToPcm(source, account, messageId, null);
  }

  private static DecodedAudio decodeToPcm(
      File source, int account, int messageId, AtomicBoolean cancellation) throws Exception {
    return decodeRange(source, account, messageId, cancellation, 0, Long.MAX_VALUE);
  }

  private static DecodedAudio decodeRange(
      File source, int account, int messageId, AtomicBoolean cancellation, long startUs, long endUs)
      throws Exception {
    MediaExtractor extractor = new MediaExtractor();
    MediaCodec decoder = null;
    FileOutputStream output = null;
    File pcm =
        new File(
            ApplicationLoader.applicationContext.getCacheDir(),
            "telepremium_stt_" + account + "_" + messageId + "_" + System.nanoTime() + ".pcm");

    int sampleRate = 16000;
    int channelCount = 1;
    int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;

    try {
      extractor.setDataSource(source.getAbsolutePath());

      MediaFormat inputFormat = null;
      int trackIndex = -1;
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat format = extractor.getTrackFormat(i);
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("audio/")) {
          trackIndex = i;
          inputFormat = format;
          break;
        }
      }
      if (trackIndex < 0 || inputFormat == null) {
        throw new IllegalStateException("No audio track");
      }

      if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
        sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
      }
      if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
        channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
      }

      String mime = inputFormat.getString(MediaFormat.KEY_MIME);
      if (TextUtils.isEmpty(mime)) {
        throw new IllegalStateException("No audio MIME");
      }

      extractor.selectTrack(trackIndex);
      if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
      decoder = MediaCodec.createDecoderByType(mime);
      decoder.configure(inputFormat, null, null, 0);
      decoder.start();

      output = new FileOutputStream(pcm, false);
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      boolean inputDone = false;
      boolean outputDone = false;

      while (!outputDone) {
        if (cancellation != null && cancellation.get()) throw new java.io.IOException("Cancelled");
        if (!inputDone) {
          int inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
          if (inputIndex >= 0) {
            ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
            if (inputBuffer == null) {
              throw new IllegalStateException("Decoder input buffer unavailable");
            }
            inputBuffer.clear();
            int sampleSize = extractor.readSampleData(inputBuffer, 0);
            if (sampleSize < 0 || extractor.getSampleTime() >= endUs) {
              decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              inputDone = true;
            } else {
              decoder.queueInputBuffer(
                  inputIndex, 0, sampleSize, Math.max(0, extractor.getSampleTime()), 0);
              extractor.advance();
            }
          }
        }

        int outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
        if (outputIndex >= 0) {
          ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
          if (outputBuffer != null
              && info.size > 0
              && info.presentationTimeUs >= startUs
              && info.presentationTimeUs < endUs) {
            ByteBuffer copy = outputBuffer.duplicate();
            copy.position(info.offset);
            copy.limit(info.offset + info.size);
            byte[] chunk = new byte[info.size];
            copy.get(chunk);
            if (pcm.length() + chunk.length > MAX_PCM_BYTES)
              throw new java.io.IOException("PCM budget");
            output.write(chunk);
          }
          decoder.releaseOutputBuffer(outputIndex, false);
          outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat outputFormat = decoder.getOutputFormat();
          if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
          }
          if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
          }
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
              && outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
          }
        }
      }

      output.flush();
      if (!pcm.exists() || pcm.length() <= 0) {
        throw new IllegalStateException("Decoded PCM is empty");
      }
      return new DecodedAudio(pcm, sampleRate, channelCount, pcmEncoding);
    } catch (Exception failure) {
      pcm.delete();
      throw failure;
    } finally {
      if (output != null) {
        try {
          output.close();
        } catch (Throwable ignore) {
        }
      }
      if (decoder != null) {
        try {
          decoder.stop();
        } catch (Throwable ignore) {
        }
        try {
          decoder.release();
        } catch (Throwable ignore) {
        }
      }
      try {
        extractor.release();
      } catch (Throwable ignore) {
      }
    }
  }

  private static final class DecodedAudio {
    final File file;
    final int sampleRate;
    final int channelCount;
    final int encoding;

    DecodedAudio(File file, int sampleRate, int channelCount, int encoding) {
      this.file = file;
      this.sampleRate = sampleRate;
      this.channelCount = channelCount;
      this.encoding = encoding;
    }
  }

  private static final class RecognitionSession implements RecognitionListener {
    private final DecodedAudio audio;
    private final Callback callback;
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final StringBuilder segmentedText = new StringBuilder();

    private SpeechRecognizer recognizer;
    private ParcelFileDescriptor descriptor;
    private final boolean onDevice;
    private String language;
    private AtomicBoolean cancellation;
    private final android.os.Handler watchdog =
        new android.os.Handler(android.os.Looper.getMainLooper());

    RecognitionSession(DecodedAudio audio, Callback callback) {
      this(audio, callback, false);
    }

    RecognitionSession(DecodedAudio audio, Callback callback, boolean onDevice) {
      this.audio = audio;
      this.callback = callback;
      this.onDevice = onDevice;
    }

    void start() throws Exception {
      descriptor = ParcelFileDescriptor.open(audio.file, ParcelFileDescriptor.MODE_READ_ONLY);
      recognizer =
          onDevice
              ? SpeechRecognizer.createOnDeviceSpeechRecognizer(
                  ApplicationLoader.applicationContext)
              : SpeechRecognizer.createSpeechRecognizer(ApplicationLoader.applicationContext);
      recognizer.setRecognitionListener(this);

      Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
      intent.putExtra(
          RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
      if (language == null || !language.equals("auto"))
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE,
            language == null ? Locale.getDefault().toLanguageTag() : language);
      else if (Build.VERSION.SDK_INT >= 34)
        intent.putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true);
      intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
      intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
      intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, descriptor);
      intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, audio.channelCount);
      intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, audio.encoding);
      intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, audio.sampleRate);
      intent.putExtra(
          RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);

      recognizer.startListening(intent);
      watchdog.postDelayed(
          () -> {
            if (!finished.get()) error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
          },
          90000);
      if (cancellation != null)
        watchdog.postDelayed(
            new Runnable() {
              public void run() {
                if (finished.get()) return;
                if (cancellation.get()) error(SpeechRecognizer.ERROR_CLIENT);
                else watchdog.postDelayed(this, 250);
              }
            },
            250);
    }

    private String best(Bundle bundle) {
      if (bundle == null) {
        return null;
      }
      ArrayList<String> results = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
      if (results == null || results.isEmpty()) {
        return null;
      }
      return results.get(0);
    }

    private void appendSegment(String text) {
      if (TextUtils.isEmpty(text)) {
        return;
      }
      String value = text.trim();
      if (value.isEmpty()) {
        return;
      }
      if (segmentedText.length() > 0) {
        segmentedText.append(' ');
      }
      segmentedText.append(value);
    }

    private void success(String text) {
      if (!finished.compareAndSet(false, true)) {
        return;
      }
      cleanup();
      if (callback != null) {
        callback.onSuccess(text == null ? "" : text.trim());
      }
    }

    private void error(int code) {
      if (!finished.compareAndSet(false, true)) {
        return;
      }
      cleanup();
      if (callback != null) {
        callback.onError("TelePremium: ошибка распознавания (" + code + ")");
      }
    }

    private void cleanup() {
      watchdog.removeCallbacksAndMessages(null);
      if (recognizer != null) {
        try {
          recognizer.destroy();
        } catch (Throwable ignore) {
        }
        recognizer = null;
      }
      if (descriptor != null) {
        try {
          descriptor.close();
        } catch (Throwable ignore) {
        }
        descriptor = null;
      }
      if (audio.file != null) {
        try {
          audio.file.delete();
        } catch (Throwable ignore) {
        }
      }
    }

    @Override
    public void onReadyForSpeech(Bundle params) {}

    @Override
    public void onBeginningOfSpeech() {}

    @Override
    public void onRmsChanged(float rmsdB) {}

    @Override
    public void onBufferReceived(byte[] buffer) {}

    @Override
    public void onEndOfSpeech() {}

    @Override
    public void onError(int error) {
      error(error);
    }

    @Override
    public void onResults(Bundle results) {
      String result = best(results);
      if (segmentedText.length() > 0) {
        if (!TextUtils.isEmpty(result) && !segmentedText.toString().endsWith(result.trim())) {
          appendSegment(result);
        }
        success(segmentedText.toString());
      } else if (!TextUtils.isEmpty(result)) {
        success(result);
      } else {
        error(SpeechRecognizer.ERROR_NO_MATCH);
      }
    }

    @Override
    public void onPartialResults(Bundle partialResults) {}

    @Override
    public void onEvent(int eventType, Bundle params) {}

    public void onSegmentResults(Bundle segmentResults) {
      appendSegment(best(segmentResults));
    }

    public void onEndOfSegmentedSession() {
      if (segmentedText.length() > 0) {
        success(segmentedText.toString());
      } else {
        error(SpeechRecognizer.ERROR_NO_MATCH);
      }
    }
  }
}
