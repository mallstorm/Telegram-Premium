package org.telegram.messenger.telepremium;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestTimeDelegate;

public class AccountNetworkController {

    private static final AccountNetworkController INSTANCE = new AccountNetworkController();

    public static AccountNetworkController getInstance() {
        return INSTANCE;
    }

    private final AccountNetworkStore store = AccountNetworkStore.getInstance();

    public boolean hasExplicitProfile(int account) {
        return store.hasProfile(account);
    }

    public AccountNetworkProfile getProfile(int account) {
        return store.load(account);
    }

    public AccountNetworkProfile getEditableProfile(int account) {
        return store.loadOrDefault(account).copy();
    }

    public void saveAndApply(int account, AccountNetworkProfile profile) {
        store.save(account, profile);
        applyProfile(account);
        if (account == UserConfig.selectedAccount) org.telegram.messenger.telepremium.xray.XrayRuntime.selectedAccountChanged();
        org.telegram.messenger.telepremium.xray.XrayRuntime.refresh();
        refreshPromo(account);
    }

    public void resetToGlobal(int account) {
        store.clear(account);
        applyInheritedGlobal(account);
        ConnectionsManager.native_resumeNetwork(account, true);
        if (account == UserConfig.selectedAccount) org.telegram.messenger.telepremium.xray.XrayRuntime.selectedAccountChanged();
        org.telegram.messenger.telepremium.xray.XrayRuntime.refresh();
        refreshPromo(account);
    }

    /** Set before native_init so a configured account never starts with the global/direct route. */
    public boolean prepareForInit(int account) {
        AccountNetworkProfile p=store.load(account);
        if(p==null||p.mode==AccountNetworkProfile.MODE_INHERIT)return false;
        ConnectionsManager.native_setIpStrategy(account,p.ipStrategy);
        if(p.mode==AccountNetworkProfile.MODE_XRAY){int port=org.telegram.messenger.telepremium.xray.XrayRuntime.currentSocksPort(p.xrayProfileId);ConnectionsManager.native_setProxySettings(account,"127.0.0.1",port>0?port:9,"","","");}
        else if(p.isConfiguredProxy())ConnectionsManager.native_setProxySettings(account,safe(p.address),p.port,safe(p.username),safe(p.password),p.mode==AccountNetworkProfile.MODE_MTPROTO?safe(p.secret):"");
        else if(p.isProxyMode())ConnectionsManager.native_setProxySettings(account,"127.0.0.1",9,"","","");
        else ConnectionsManager.native_setProxySettings(account,"",1080,"","","");
        return true;
    }

    public void applyProfile(int account) {
        AccountNetworkProfile profile = store.load(account);
        if (profile == null || profile.mode == AccountNetworkProfile.MODE_INHERIT) {
            applyInheritedGlobal(account);
            return;
        }

        ConnectionsManager.native_setIpStrategy(account, profile.ipStrategy);
        if (profile.mode == AccountNetworkProfile.MODE_XRAY) {
            final long user = UserConfig.getInstance(account).getClientUserId();
            int port = org.telegram.messenger.telepremium.xray.XrayRuntime.currentSocksPort(profile.xrayProfileId);
            ConnectionsManager.native_setProxySettings(account, "127.0.0.1", port > 0 ? port : 9, "", "", "");
            ConnectionsManager.native_resumeNetwork(account, true);
            if (port == 0) org.telegram.messenger.telepremium.xray.XrayRuntime.ensureSocks(profile.xrayProfileId, (ready, error) -> {
                AccountNetworkProfile latest = store.load(account);
                if (UserConfig.getInstance(account).getClientUserId() != user || latest == null
                        || latest.mode != AccountNetworkProfile.MODE_XRAY || !profile.xrayProfileId.equals(latest.xrayProfileId)) return;
                ConnectionsManager.native_setProxySettings(account, "127.0.0.1", ready > 0 ? ready : 9, "", "", "");
                ConnectionsManager.native_resumeNetwork(account, true);
            });
            return;
        }


        if (profile.isConfiguredProxy()) {
            ConnectionsManager.native_setProxySettings(
                    account,
                    safe(profile.address),
                    profile.port,
                    safe(profile.username),
                    safe(profile.password),
                    profile.mode == AccountNetworkProfile.MODE_MTPROTO ? safe(profile.secret) : ""
            );
        } else if (profile.isProxyMode()) {
            ConnectionsManager.native_setProxySettings(account, "127.0.0.1", 9, "", "", "");
        } else {
            ConnectionsManager.native_setProxySettings(account, "", 1080, "", "", "");
        }

        ConnectionsManager.native_resumeNetwork(account, true);
    }

    public long checkProfile(int account, AccountNetworkProfile profile, RequestTimeDelegate delegate) {
        if (profile != null && profile.mode == AccountNetworkProfile.MODE_XRAY) {
            org.telegram.messenger.telepremium.xray.XrayRuntime.ensureSocks(profile.xrayProfileId, (port,error) -> {
                if (port <= 0) { if(delegate!=null)delegate.run(-1); return; }
                org.telegram.utils.proxy.ProxySettings settings=org.telegram.utils.proxy.ProxySettings.builder()
                        .setType(org.telegram.utils.proxy.ProxySettings.Type.SOCKS5).setAddress("127.0.0.1").setPort(port).setUser("").setPassword("").setSecret("").build();
                ConnectionsManager.getInstance(account).checkProxy(settings,delegate);
            });
            return 0;
        }
        if (profile != null && profile.isConfiguredProxy()) {
            String secret = profile.mode == AccountNetworkProfile.MODE_MTPROTO ? safe(profile.secret) : "";
            org.telegram.utils.proxy.ProxySettings settings = org.telegram.utils.proxy.ProxySettings.builder()
                    .setType(secret.isEmpty() ? org.telegram.utils.proxy.ProxySettings.Type.SOCKS5 : org.telegram.utils.proxy.ProxySettings.Type.MTPROTO)
                    .setAddress(safe(profile.address))
                    .setPort(profile.port)
                    .setUser(safe(profile.username))
                    .setPassword(safe(profile.password))
                    .setSecret(secret)
                    .build();
            return ConnectionsManager.getInstance(account).checkProxy(settings, delegate);
        }

        if (delegate != null) {
            delegate.run(ConnectionsManager.native_getCurrentPingTime(account));
        }
        return 0;
    }

    public String getSummary(int account) {
        AccountNetworkProfile profile = store.load(account);
        if (profile == null || profile.mode == AccountNetworkProfile.MODE_INHERIT) {
            return "Общий маршрут";
        }

        StringBuilder out = new StringBuilder(AccountNetworkProfile.modeLabel(profile.mode));
        if (!TextUtils.isEmpty(profile.locationName)) {
            out.append(" • ").append(profile.locationName);
        } else if (!TextUtils.isEmpty(profile.displayName)) {
            out.append(" • ").append(profile.displayName);
        }
        return out.toString();
    }

    public int getPing(int account) {
        return ConnectionsManager.native_getCurrentPingTime(account);
    }

    public int getConnectionState(int account) {
        return ConnectionsManager.getInstance(account).getConnectionState();
    }

    private void applyInheritedGlobal(int account) {
        SharedPreferences p = ApplicationLoader.applicationContext.getSharedPreferences(
                "mainconfig",
                Activity.MODE_PRIVATE
        );

        String address = p.getString("proxy_ip", "");
        String username = p.getString("proxy_user", "");
        String password = p.getString("proxy_pass", "");
        String secret = p.getString("proxy_secret", "");
        int port = p.getInt("proxy_port", 1080);
        boolean enabled = p.getBoolean("proxy_enabled", false);

        if (enabled && !TextUtils.isEmpty(address)) {
            ConnectionsManager.native_setProxySettings(
                    account,
                    address,
                    port,
                    safe(username),
                    safe(password),
                    safe(secret)
            );
        } else {
            ConnectionsManager.native_setProxySettings(account, "", 1080, "", "", "");
        }

    }

    private void refreshPromo(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return;
        }
        AccountInstance instance = AccountInstance.getInstance(account);
        if (instance.getUserConfig().isClientActivated()) {
            instance.getMessagesController().checkPromoInfo(true);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
