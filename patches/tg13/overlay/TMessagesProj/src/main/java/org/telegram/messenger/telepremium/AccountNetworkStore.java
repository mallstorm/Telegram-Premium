package org.telegram.messenger.telepremium;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

public class AccountNetworkStore {

    private static final AccountNetworkStore INSTANCE = new AccountNetworkStore();
    private static final String PREFIX = "telepremium_network_account_";

    public static AccountNetworkStore getInstance() {
        return INSTANCE;
    }

    private SharedPreferences targetPrefs(int account) {
        long uid = UserConfig.getInstance(account).getClientUserId();
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFIX + (uid > 0 ? "u" + uid : "pending_" + account), Context.MODE_PRIVATE);
    }
    private SharedPreferences prefs(int account) {
        SharedPreferences target = targetPrefs(account);
        if (!target.getBoolean("configured", false)) {
            long uid = UserConfig.getInstance(account).getClientUserId();
            SharedPreferences legacy = ApplicationLoader.applicationContext.getSharedPreferences(PREFIX + account, Context.MODE_PRIVATE);
            if (uid > 0 && legacy.getLong("bound_user_id", 0) == uid && legacy.getBoolean("configured", false)) {
                SharedPreferences.Editor copy = target.edit();
                for (java.util.Map.Entry<String, ?> entry : legacy.getAll().entrySet()) {
                    Object value = entry.getValue(); String key = entry.getKey();
                    if (value instanceof String) copy.putString(key, (String)value);
                    else if (value instanceof Boolean) copy.putBoolean(key, (Boolean)value);
                    else if (value instanceof Integer) copy.putInt(key, (Integer)value);
                    else if (value instanceof Long) copy.putLong(key, (Long)value);
                }
                if (copy.commit()) return target;
                return legacy;
            }
        }
        return target;
    }

    public boolean hasProfile(int account) {
        return load(account) != null;
    }

    public AccountNetworkProfile load(int account) {
        SharedPreferences p = prefs(account);
        if (!p.getBoolean("configured", false)) {
            return null;
        }

        long currentUserId = UserConfig.getInstance(account).getClientUserId();
        long boundUserId = p.getLong("bound_user_id", 0);
        if (boundUserId != 0 && currentUserId != 0 && boundUserId != currentUserId) {
            return null;
        }

        AccountNetworkProfile profile = new AccountNetworkProfile();
        profile.mode = p.getInt("mode", AccountNetworkProfile.MODE_INHERIT);
        profile.xrayProfileId = p.getString("xray_profile_id", "");
        profile.displayName = p.getString("display_name", "");
        profile.locationName = p.getString("location_name", "");
        profile.address = p.getString("address", "");
        profile.port = p.getInt("port", 1080);
        profile.username = p.getString("username", "");
        profile.password = p.getString("password", "");
        profile.secret = p.getString("secret", "");
        profile.ipStrategy = (byte) p.getInt("ip_strategy", 2);
        profile.fallbackEnabled = p.getBoolean("fallback_enabled", true);
        return profile;
    }

    public AccountNetworkProfile loadOrDefault(int account) {
        AccountNetworkProfile profile = load(account);
        return profile != null ? profile : new AccountNetworkProfile();
    }

    public void save(int account, AccountNetworkProfile profile) {
        long currentUserId = UserConfig.getInstance(account).getClientUserId();
        targetPrefs(account).edit()
                .putBoolean("configured", true)
                .putLong("bound_user_id", currentUserId)
                .putInt("mode", profile.mode)
                .putString("xray_profile_id", safe(profile.xrayProfileId))
                .putString("display_name", safe(profile.displayName))
                .putString("location_name", safe(profile.locationName))
                .putString("address", safe(profile.address))
                .putInt("port", profile.port)
                .putString("username", safe(profile.username))
                .putString("password", safe(profile.password))
                .putString("secret", safe(profile.secret))
                .putInt("ip_strategy", profile.ipStrategy)
                .putBoolean("fallback_enabled", profile.fallbackEnabled)
                .apply();
    }

    public void clear(int account) {
        targetPrefs(account).edit().clear().apply();
        SharedPreferences legacy = ApplicationLoader.applicationContext.getSharedPreferences(PREFIX + account, Context.MODE_PRIVATE);
        if (legacy.getLong("bound_user_id", 0) == UserConfig.getInstance(account).getClientUserId()) legacy.edit().clear().apply();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
