package org.telegram.messenger.telepremium;

import android.content.Context;
import android.content.SharedPreferences;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;

/** Per-account invisible mode: filters outgoing presence/read requests before they reach the network. */
public final class TelePremiumGhost {
    public static final int ONLINE = 1, READ = 2, TYPING = 4;
    private TelePremiumGhost() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("telepremium_ghost", Context.MODE_PRIVATE);
    }

    private static String key(int account) {
        long uid = UserConfig.getInstance(account).getClientUserId();
        return uid > 0 ? "u" + uid : "";
    }

    public static int flags(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return 0;
        String k = key(account);
        return k.isEmpty() ? 0 : prefs().getInt(k, 0);
    }

    public static boolean has(int account, int flag) {
        return (flags(account) & flag) != 0;
    }

    public static boolean isActive(int account) {
        return flags(account) != 0;
    }

    public static void set(int account, int flag, boolean on) {
        String k = key(account);
        if (k.isEmpty()) return;
        int value = flags(account);
        value = on ? (value | flag) : (value & ~flag);
        prefs().edit().putInt(k, value).apply();
        if (on && flag == ONLINE) forceOffline(account);
    }

    private static void forceOffline(int account) {
        try {
            org.telegram.tgnet.tl.TL_account.updateStatus req = new org.telegram.tgnet.tl.TL_account.updateStatus();
            req.offline = true;
            org.telegram.tgnet.ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {});
        } catch (Throwable ignored) {
        }
    }

    /** @return true when the request must not be sent. May rewrite the request in place. */
    public static boolean filter(int account, TLObject request) {
        if (request == null) return false;
        int f = flags(account);
        if (f == 0) return false;
        String n = request.getClass().getSimpleName();
        if ((f & ONLINE) != 0 && (n.equals("updateStatus") || n.equals("TL_account_updateStatus"))) {
            try {
                request.getClass().getField("offline").setBoolean(request, true);
            } catch (Throwable e) {
                return true;
            }
            return false;
        }
        if ((f & READ) != 0) {
            switch (n) {
                case "TL_messages_readHistory":
                case "TL_channels_readHistory":
                case "TL_messages_readMessageContents":
                case "TL_channels_readMessageContents":
                case "TL_stories_readStories":
                case "readStories":
                    return true;
                default:
            }
        }
        if ((f & TYPING) != 0 && (n.equals("TL_messages_setTyping") || n.equals("setTyping"))) return true;
        return false;
    }
}
