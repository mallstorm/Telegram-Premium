package org.telegram.messenger.telepremium;

/** Compile-time TelePremium feature switches that alter upstream Telegram behaviour. */
public final class TelePremiumFeatures {
    /** Matches the 11.4.2 line: sponsored channel messages are not requested. */
    public static final boolean DISABLE_SPONSORED_MESSAGES = true;

    /** Telegram's own APK update channel is never used by TelePremium builds. */
    public static final boolean DISABLE_TELEGRAM_UPDATER = true;

    private TelePremiumFeatures() {}
}
