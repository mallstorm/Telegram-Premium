package org.telegram.messenger.telepremium.theme;

import android.content.Context;
import android.graphics.Color;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TelePremiumThemeResources {
    private TelePremiumThemeResources() {}

    public static String buildAttheme(Context context, TelePremiumThemePreset p) {
        LinkedHashMap<String, Integer> c = new LinkedHashMap<>();
        int shellAccent = resolveAccent(context, p, false);
        int chatAccent = resolveAccent(context, p, true);
        int chatBackground = resolveChatBackground(context, p);
        int outgoingBubble = resolveOutgoingBubble(context, p, chatAccent);
        int incomingBubble = TelePremiumThemeStore.getChatBackgroundMode(context) == TelePremiumThemeStore.CHAT_BG_CLEAN ? p.surface : p.incomingBubble;

        c.put("windowBackgroundWhite", p.surface); c.put("windowBackgroundGray", p.background); c.put("windowBackgroundWhiteBlackText", p.textPrimary);
        c.put("windowBackgroundWhiteGrayText", p.textSecondary); c.put("windowBackgroundWhiteGrayText2", p.textSecondary); c.put("windowBackgroundWhiteGrayText3", p.textSecondary);
        c.put("windowBackgroundWhiteValueText", shellAccent); c.put("windowBackgroundWhiteHintText", p.textSecondary); c.put("windowBackgroundWhiteLinkText", shellAccent);
        c.put("windowBackgroundWhiteBlueText", shellAccent); c.put("windowBackgroundWhiteBlueText2", shellAccent); c.put("windowBackgroundWhiteBlueHeader", shellAccent);
        c.put("windowBackgroundWhiteBlueButton", shellAccent); c.put("windowBackgroundWhiteBlueButtonPressed", darken(shellAccent, 0.12f));
        c.put("windowBackgroundGrayShadow", p.divider); c.put("divider", p.divider); c.put("listSelector", p.listSelector);
        c.put("actionBarDefault", p.actionBar); c.put("actionBarDefaultIcon", p.iconPrimary); c.put("actionBarDefaultTitle", p.textPrimary); c.put("actionBarDefaultSubtitle", p.textSecondary);
        c.put("actionBarDefaultSelector", p.listSelector); c.put("actionBarDefaultSearch", p.textPrimary); c.put("actionBarDefaultSearchPlaceholder", p.textSecondary);
        c.put("chats_menuBackground", p.drawerBackground); c.put("chats_menuTopBackground", p.background); c.put("chats_menuTopBackgroundCats", p.background);
        c.put("chats_menuTopShadow", p.divider); c.put("chats_menuTopShadowCats", p.divider); c.put("chats_menuName", p.textPrimary); c.put("chats_menuPhone", p.textSecondary);
        c.put("chats_menuPhoneCats", p.textSecondary); c.put("chats_menuItemText", p.textPrimary); c.put("chats_menuItemIcon", shellAccent); c.put("chats_menuItemCheck", shellAccent);
        c.put("chats_name", p.textPrimary); c.put("chats_message", p.textSecondary); c.put("chats_date", p.textSecondary); c.put("chats_unreadCounter", shellAccent);
        c.put("chats_unreadCounterText", contrastOn(shellAccent)); c.put("chats_actionBackground", shellAccent); c.put("chats_actionBackgroundPressed", darken(shellAccent, 0.12f)); c.put("chats_actionIcon", contrastOn(shellAccent));

        c.put("chat_wallpaper", chatBackground); c.put("chat_inBubble", incomingBubble); c.put("chat_outBubble", outgoingBubble);
        c.put("chat_messageTextIn", p.textPrimary); c.put("chat_messageTextOut", p.textPrimary); c.put("chat_inTimeText", p.textSecondary); c.put("chat_outTimeText", p.textSecondary);
        c.put("chat_outSentCheck", chatAccent); c.put("chat_outSentCheckSelected", chatAccent); c.put("chat_inReplyLine", chatAccent); c.put("chat_outReplyLine", chatAccent);
        c.put("chat_inReplyNameText", chatAccent); c.put("chat_outReplyNameText", chatAccent); c.put("chat_inPreviewLine", chatAccent); c.put("chat_outPreviewLine", chatAccent);
        c.put("chat_serviceBackground", TelePremiumThemeRegistry.blend(chatBackground, p.textPrimary, p.isDark ? 0.14f : 0.08f)); c.put("chat_serviceText", p.textPrimary);
        c.put("chat_linkSelectBackground", TelePremiumThemeRegistry.blend(chatAccent, chatBackground, 0.72f)); c.put("chat_messagePanelBackground", p.surface); c.put("chat_messagePanelText", p.textPrimary);
        c.put("chat_messagePanelHint", p.textSecondary); c.put("chat_messagePanelIcons", chatAccent); c.put("chat_messagePanelSend", chatAccent); c.put("chat_messagePanelCursor", chatAccent);
        c.put("chat_messagePanelVoicePressed", chatAccent); c.put("chat_recordTime", p.textSecondary); c.put("chat_emojiPanelBackground", p.surface);

        c.put("dialogBackground", p.surfaceElevated); c.put("dialogTextBlack", p.textPrimary); c.put("dialogTextGray", p.textSecondary); c.put("dialogTextGray2", p.textSecondary);
        c.put("dialogButton", shellAccent); c.put("dialogIcon", shellAccent); c.put("dialogInputField", p.inputBackground); c.put("dialogInputFieldActivated", shellAccent);
        c.put("radioBackground", p.iconSecondary); c.put("radioBackgroundChecked", shellAccent); c.put("switchTrack", p.divider); c.put("switchTrackChecked", shellAccent);
        c.put("checkbox", p.iconSecondary); c.put("checkboxCheck", contrastOn(shellAccent)); c.put("checkboxSquareBackground", shellAccent); c.put("checkboxSquareCheck", contrastOn(shellAccent));
        c.put("progressCircle", shellAccent); c.put("featuredStickers_addButton", shellAccent); c.put("featuredStickers_buttonText", contrastOn(shellAccent));

        StringBuilder out = new StringBuilder(4600);
        for (Map.Entry<String, Integer> e : c.entrySet()) out.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        return out.toString();
    }

    public static int resolveAccent(Context context, TelePremiumThemePreset p, boolean chat) {
        int explicit = TelePremiumThemeStore.getAccentOverride(context);
        if (!chat && explicit != 0) return explicit;
        int mode = chat ? TelePremiumThemeStore.getChatAccentMode(context) : TelePremiumThemeStore.getShellAccentMode(context);
        if (mode == TelePremiumThemeStore.MODE_SOFT) return TelePremiumThemeRegistry.blend(p.accent, p.background, p.isDark ? 0.13f : 0.17f);
        if (mode == TelePremiumThemeStore.MODE_STRONG) return darken(p.accent, p.isDark ? 0.04f : 0.12f);
        return p.accent;
    }

    public static int resolveChatBackground(Context context, TelePremiumThemePreset p) {
        int mode = TelePremiumThemeStore.getChatBackgroundMode(context);
        if (mode == TelePremiumThemeStore.CHAT_BG_CLEAN) return p.surface;
        if (mode == TelePremiumThemeStore.CHAT_BG_ARTWORK) return TelePremiumThemeRegistry.blend(p.background, p.accent, p.isDark ? 0.055f : 0.035f);
        return p.background;
    }

    public static int resolveOutgoingBubble(Context context, TelePremiumThemePreset p, int chatAccent) {
        int mode = TelePremiumThemeStore.getChatAccentMode(context); float mix = p.isDark ? 0.27f : 0.14f;
        if (mode == TelePremiumThemeStore.MODE_SOFT) mix = p.isDark ? 0.20f : 0.10f;
        if (mode == TelePremiumThemeStore.MODE_STRONG) mix = p.isDark ? 0.35f : 0.20f;
        return TelePremiumThemeRegistry.blend(p.surface, chatAccent, mix);
    }

    private static int darken(int color, float amount) { return TelePremiumThemeRegistry.blend(color, Color.BLACK, amount); }
    private static int contrastOn(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
        return luminance > 0.66 ? 0xff17212b : 0xffffffff;
    }
}
