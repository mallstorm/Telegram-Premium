package org.telegram.messenger.telepremium.theme;

import android.content.Context;
import android.graphics.Color;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TelePremiumThemeResources {
    private TelePremiumThemeResources() {}

    public static String buildAttheme(Context context, TelePremiumThemePreset p) {
        LinkedHashMap<String, Integer> c = new LinkedHashMap<>();
        // Dark presets start from Telegram's complete night palette: keys a preset does not set
        // (popup menus, sheets, pickers, story UI…) must not fall back to the light defaults —
        // that was the white "⋮" menu on dark themes.
        // Use a complete Telegram base palette before our surface overrides.
        // The preset ID itself is shared by classic 11.x and native 13.x shells.
        // Missing optional bundled assets safely fall back to Telegram defaults.
        loadBase(context, p.isDark ? "night.attheme" : "day.attheme", c);
        int shellAccent = resolveAccent(context, p, false);
        int chatAccent = resolveAccent(context, p, true);
        int chatBackground = resolveChatBackground(context, p);
        int outgoingBubble = resolveOutgoingBubble(context, p, chatAccent);
        int incomingBubble = TelePremiumThemeStore.getChatBackgroundMode(context) == TelePremiumThemeStore.CHAT_BG_CLEAN
                ? p.surface : p.incomingBubble;

        c.put("windowBackgroundWhite", p.surface);
        c.put("windowBackgroundGray", p.background);
        c.put("windowBackgroundWhiteBlackText", p.textPrimary);
        c.put("windowBackgroundWhiteGrayText", p.textSecondary);
        c.put("windowBackgroundWhiteGrayText2", p.textSecondary);
        c.put("windowBackgroundWhiteGrayText3", p.textSecondary);
        c.put("windowBackgroundWhiteValueText", shellAccent);
        c.put("windowBackgroundWhiteHintText", p.textSecondary);
        c.put("windowBackgroundWhiteLinkText", shellAccent);
        c.put("windowBackgroundWhiteBlueText", shellAccent);
        c.put("windowBackgroundWhiteBlueText2", shellAccent);
        c.put("windowBackgroundWhiteBlueHeader", shellAccent);
        c.put("windowBackgroundWhiteBlueButton", shellAccent);
        c.put("windowBackgroundWhiteBlueButtonPressed", darken(shellAccent, 0.12f));
        c.put("windowBackgroundGrayShadow", p.divider);
        c.put("divider", p.divider);
        c.put("listSelector", p.listSelector);

        c.put("actionBarDefault", p.actionBar);
        c.put("actionBarDefaultIcon", p.iconPrimary);
        c.put("actionBarDefaultTitle", p.textPrimary);
        c.put("actionBarDefaultSubtitle", p.textSecondary);
        c.put("actionBarDefaultSelector", p.listSelector);
        c.put("actionBarDefaultArchived", p.actionBar);
        c.put("actionBarDefaultArchivedIcon", p.iconPrimary);
        c.put("actionBarDefaultArchivedTitle", p.textPrimary);
        c.put("actionBarTabActiveText", shellAccent);
        c.put("actionBarTabUnactiveText", p.textSecondary);
        c.put("actionBarTabLine", shellAccent);
        c.put("actionBarTabSelector", p.listSelector);
        c.put("actionBarDefaultSearch", p.textPrimary);
        c.put("actionBarDefaultSearchPlaceholder", p.textSecondary);
        c.put("actionBarDefaultSubmenuBackground", p.surfaceElevated);
        c.put("actionBarDefaultSubmenuItem", p.textPrimary);
        c.put("actionBarDefaultSubmenuItemIcon", p.iconPrimary);
        c.put("actionBarDefaultSubmenuSeparator", p.isDark ? TelePremiumThemeRegistry.blend(p.surfaceElevated, Color.BLACK, 0.25f) : p.background);
        c.put("actionBarDefaultSubmenuSelector", p.listSelector);
        c.put("actionBarActionModeDefault", p.actionBar);
        c.put("actionBarActionModeDefaultIcon", p.iconPrimary);
        c.put("actionBarActionModeDefaultTop", p.actionBar);
        c.put("actionBarActionModeDefaultSelector", p.listSelector);
        c.put("dialogBackgroundGray", p.background);
        c.put("dialogTextBlue", shellAccent);
        c.put("dialogTextBlue2", shellAccent);
        c.put("dialogTextLink", shellAccent);
        c.put("dialogLineProgress", shellAccent);
        c.put("dialogGrayLine", p.divider);
        c.put("dialogScrollGlow", p.background);
        c.put("dialogSearchBackground", p.inputBackground);
        c.put("dialogSearchText", p.textPrimary);
        c.put("dialogSearchHint", p.textSecondary);
        c.put("dialogSearchIcon", p.textSecondary);

        c.put("chats_menuBackground", p.drawerBackground);
        c.put("chats_menuTopBackground", p.background);
        c.put("chats_menuTopBackgroundCats", p.background);
        c.put("chats_menuTopShadow", p.divider);
        c.put("chats_menuTopShadowCats", p.divider);
        c.put("chats_menuName", p.textPrimary);
        c.put("chats_menuPhone", p.textSecondary);
        c.put("chats_menuPhoneCats", p.textSecondary);
        c.put("chats_menuItemText", p.textPrimary);
        c.put("chats_menuItemIcon", shellAccent);
        c.put("chats_menuItemCheck", shellAccent);
        c.put("chats_menuItemSelector", p.listSelector);
        c.put("chats_menuItemTextRed", p.textPrimary);
        c.put("chats_menuName", p.textPrimary);
        c.put("chats_searchText", p.textPrimary);
        c.put("chats_searchHint", p.textSecondary);
        c.put("chats_searchIcon", p.iconSecondary);
        c.put("chats_name", p.textPrimary);
        c.put("chats_message", p.textSecondary);
        c.put("chats_messageArchived", p.textSecondary);
        c.put("chats_muteIcon", p.iconSecondary);
        c.put("chats_pinnedIcon", p.iconSecondary);
        c.put("chats_secretName", shellAccent);
        c.put("chats_date", p.textSecondary);
        c.put("chats_unreadCounter", shellAccent);
        c.put("chats_unreadCounterText", contrastOn(shellAccent));
        c.put("chats_actionBackground", shellAccent);
        c.put("chats_actionBackgroundPressed", darken(shellAccent, 0.12f));
        c.put("chats_actionIcon", contrastOn(shellAccent));

        // Chat-list widgets that otherwise keep Telegram's stock blue on non-blue presets; both
        // shells render the same dialog cells, so this is what makes a preset "sit" on either one.
        c.put("chats_sentCheck", shellAccent);
        c.put("chats_sentReadCheck", shellAccent);
        c.put("chats_sentClock", p.textSecondary);
        c.put("chats_nameMessage", shellAccent);
        c.put("chats_nameMessage_threeLines", shellAccent);
        c.put("chats_attachMessage", shellAccent);
        c.put("chats_actionMessage", shellAccent);
        c.put("chats_verifiedBackground", shellAccent);
        c.put("chats_verifiedCheck", contrastOn(shellAccent));
        c.put("chats_unreadCounterMuted", p.iconSecondary);
        c.put("chats_tabUnreadActiveBackground", shellAccent);
        c.put("chats_tabUnreadUnactiveBackground", p.iconSecondary);
        c.put("chats_pinnedOverlay", TelePremiumThemeRegistry.blend(p.surface, p.textPrimary, p.isDark ? 0.06f : 0.04f));
        c.put("chats_archiveBackground", shellAccent);
        c.put("chats_archivePinBackground", p.iconSecondary);
        c.put("chats_archiveIcon", contrastOn(shellAccent));
        c.put("chats_archiveText", contrastOn(shellAccent));
        c.put("inappPlayerPlayPause", shellAccent);
        c.put("inappPlayerTitle", p.textPrimary);
        c.put("inappPlayerPerformer", p.textSecondary);
        c.put("inappPlayerClose", p.iconSecondary);
        c.put("windowBackgroundWhiteInputField", p.divider);
        c.put("windowBackgroundWhiteInputFieldActivated", shellAccent);
        c.put("profile_actionBackground", shellAccent);
        c.put("profile_actionIcon", contrastOn(shellAccent));

        // Chat-only layer. Stock Chat Settings may change message size, wallpaper and
        // bubble geometry, but TelePremium remains owner of the application shell.
        c.put("chat_wallpaper", chatBackground);
        c.put("chat_inBubble", incomingBubble);
        c.put("chat_outBubble", outgoingBubble);
        c.put("chat_messageTextIn", p.textPrimary);
        c.put("chat_messageTextOut", p.textPrimary);
        c.put("chat_inTimeText", p.textSecondary);
        c.put("chat_outTimeText", p.textSecondary);
        c.put("chat_outSentCheck", chatAccent);
        c.put("chat_outSentCheckSelected", chatAccent);
        c.put("chat_inReplyLine", chatAccent);
        c.put("chat_outReplyLine", chatAccent);
        c.put("chat_inReplyNameText", chatAccent);
        c.put("chat_outReplyNameText", chatAccent);
        c.put("chat_inPreviewLine", chatAccent);
        c.put("chat_outPreviewLine", chatAccent);
        c.put("chat_serviceBackground", TelePremiumThemeRegistry.blend(chatBackground, p.textPrimary, p.isDark ? 0.14f : 0.08f));
        c.put("chat_serviceText", p.textPrimary);
        c.put("chat_linkSelectBackground", TelePremiumThemeRegistry.blend(chatAccent, chatBackground, 0.72f));
        c.put("chat_messagePanelBackground", p.surface);
        c.put("chat_messagePanelText", p.textPrimary);
        c.put("chat_messagePanelHint", p.textSecondary);
        c.put("chat_messagePanelIcons", chatAccent);
        c.put("chat_messagePanelSend", chatAccent);
        c.put("chat_messagePanelCursor", chatAccent);
        c.put("chat_messagePanelVoicePressed", chatAccent);
        c.put("chat_recordTime", p.textSecondary);
        c.put("chat_emojiPanelBackground", p.surface);

        c.put("dialogBackground", p.surfaceElevated);
        c.put("dialogTextBlack", p.textPrimary);
        c.put("dialogTextGray", p.textSecondary);
        c.put("dialogTextGray2", p.textSecondary);
        c.put("dialogButton", shellAccent);
        c.put("dialogIcon", shellAccent);
        c.put("dialogInputField", p.inputBackground);
        c.put("dialogInputFieldActivated", shellAccent);
        c.put("radioBackground", p.iconSecondary);
        c.put("radioBackgroundChecked", shellAccent);
        c.put("switchTrack", p.divider);
        c.put("switchTrackChecked", shellAccent);
        c.put("checkbox", p.iconSecondary);
        c.put("checkboxCheck", contrastOn(shellAccent));
        c.put("checkboxSquareBackground", shellAccent);
        c.put("checkboxSquareCheck", contrastOn(shellAccent));
        c.put("progressCircle", shellAccent);
        c.put("featuredStickers_addButton", shellAccent);
        c.put("featuredStickers_buttonText", contrastOn(shellAccent));

        StringBuilder out = new StringBuilder(4600);
        for (Map.Entry<String, Integer> e : c.entrySet()) {
            out.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return out.toString();
    }

    private static void loadBase(Context context, String asset, Map<String, Integer> out) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(context.getAssets().open(asset), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("WPS") || line.startsWith("WLS")) break;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim(), value = line.substring(eq + 1).trim();
                try {
                    out.put(key, value.startsWith("#") ? (int) Long.parseLong(value.substring(1), 16) : Integer.parseInt(value));
                } catch (NumberFormatException ignore) {
                }
            }
        } catch (Exception ignore) {
        }
    }

    public static int resolveAccent(Context context, TelePremiumThemePreset p, boolean chat) {
        int explicit = TelePremiumThemeStore.getAccentOverride(context);
        if (!chat && explicit != 0) return explicit;
        int mode = chat ? TelePremiumThemeStore.getChatAccentMode(context) : TelePremiumThemeStore.getShellAccentMode(context);
        if (mode == TelePremiumThemeStore.MODE_SOFT) {
            return TelePremiumThemeRegistry.blend(p.accent, p.background, p.isDark ? 0.13f : 0.17f);
        }
        if (mode == TelePremiumThemeStore.MODE_STRONG) {
            return darken(p.accent, p.isDark ? 0.04f : 0.12f);
        }
        return p.accent;
    }

    public static int resolveChatBackground(Context context, TelePremiumThemePreset p) {
        int mode = TelePremiumThemeStore.getChatBackgroundMode(context);
        if (mode == TelePremiumThemeStore.CHAT_BG_CLEAN) return p.surface;
        if (mode == TelePremiumThemeStore.CHAT_BG_ARTWORK) {
            return TelePremiumThemeRegistry.blend(p.background, p.accent, p.isDark ? 0.055f : 0.035f);
        }
        return p.background;
    }

    public static int resolveOutgoingBubble(Context context, TelePremiumThemePreset p, int chatAccent) {
        int mode = TelePremiumThemeStore.getChatAccentMode(context);
        float mix = p.isDark ? 0.27f : 0.14f;
        if (mode == TelePremiumThemeStore.MODE_SOFT) mix = p.isDark ? 0.20f : 0.10f;
        if (mode == TelePremiumThemeStore.MODE_STRONG) mix = p.isDark ? 0.35f : 0.20f;
        return TelePremiumThemeRegistry.blend(p.surface, chatAccent, mix);
    }

    private static int darken(int color, float amount) {
        return TelePremiumThemeRegistry.blend(color, Color.BLACK, amount);
    }

    private static int contrastOn(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
        return luminance > 0.66 ? 0xff17212b : 0xffffffff;
    }
}
