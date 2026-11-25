package org.telegram.messenger.telepremium.aicenter;

/** A cache entry restores only the fields produced by that operation. */
public final class CenterMediaResults {
  private CenterMediaResults() {}

  public static void invalidateTranslation(CenterData.Message message) {
    message.translation = "";
    message.translationLanguage = "";
    message.translationComplete = false;
  }

  public static void apply(String operation, CenterData.Message target, CenterData.Message cached) {
    if (operation.equals("transcribe") || operation.equals("cloudAsr")) {
      if (!target.transcript.equals(cached.transcript)) invalidateTranslation(target);
      target.transcript = cached.transcript;
      target.transcriptKey = cached.transcriptKey;
    } else if (operation.equals("describe")) {
      target.description = cached.description;
      target.mediaAnalysis = cached.mediaAnalysis;
    } else {
      if (!operation.equals("download")) target.ocr = cached.ocr;
      target.hash = cached.hash;
      target.sceneHash = cached.sceneHash;
      target.pages = cached.pages;
      target.frameTimes.clear();
      target.frameTimes.addAll(cached.frameTimes);
      target.frameEvidence = cached.frameEvidence;
      target.pageEvidence = cached.pageEvidence;
      target.previewBase = cached.previewBase.isEmpty() ? cached.qualified() : cached.previewBase;
    }
    if (cached.duration > 0) target.duration = cached.duration;
  }
}
