package org.telegram.ui;

import android.content.Context;
import android.widget.*;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.telepremium.*;
import org.telegram.messenger.telepremium.xray.*;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Cells.TextCell;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.*;

/**
 * Connection manager: one place for every route. Accounts, media library, keys (each with its own
 * check, assignment and settings), subscriptions and diagnostics.
 */
public final class TelePremiumXrayProfilesActivity extends BaseFragment {
    private LinearLayout content;
    private boolean visible;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            render();
            AndroidUtilities.runOnUIThread(this, 4000);
        }
    };
    private String t(String en, String ru) { return TPStrings.isRussian() ? ru : en; }

    @Override public android.view.View createView(Context context) {
        actionBar.setTitle(t("Connection manager", "Менеджер соединений"));
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() { @Override public void onItemClick(int id) { if (id == -1) finishFragment(); } });
        ScrollView scroll = new ScrollView(context);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(32));
        scroll.addView(content);
        fragmentView = scroll;
        render();
        return scroll;
    }

    @Override public void onResume() {
        super.onResume();
        visible = true;
        render();
        AndroidUtilities.cancelRunOnUIThread(ticker);
        AndroidUtilities.runOnUIThread(ticker, 4000);
    }

    @Override public void onPause() {
        super.onPause();
        visible = false;
        AndroidUtilities.cancelRunOnUIThread(ticker);
    }

    // ------------------------------------------------------------------ small views

    private TextCell cell(String title, String value, Runnable click) {
        TextCell c = new TextCell(getParentActivity(), getResourceProvider());
        c.setTextAndValue(title, value, true);
        c.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        content.addView(c, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(60)));
        if (click != null) c.setOnClickListener(v -> click.run());
        return c;
    }
    private void label(String text) {
        TextView v = new TextView(getParentActivity());
        v.setText(text);
        v.setTextSize(14);
        v.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        v.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(16), AndroidUtilities.dp(20), AndroidUtilities.dp(10));
        content.addView(v);
    }
    private void toast(String text) { if (getParentActivity() != null) Toast.makeText(getParentActivity(), text, Toast.LENGTH_LONG).show(); }
    private void error(String value) {
        if (getParentActivity() != null) showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle(t("Connection manager", "Менеджер соединений")).setMessage(value).setPositiveButton("OK", null).create());
    }

    // ------------------------------------------------------------------ screen

    private void render() {
        if (content == null || getParentActivity() == null) return;
        content.removeAllViews();
        content.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        try {
            List<XrayProfile> profiles = XrayProfileStore.profiles();
            label(t("Core: ", "Ядро: ") + XrayRuntime.status());

            label(t("Telegram accounts", "Telegram-аккаунты"));
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) if (UserConfig.getInstance(a).isClientActivated()) {
                final int account = a;
                String name = UserObject.getUserName(UserConfig.getInstance(a).getCurrentUser());
                cell(name, accountSummary(a), () -> accountMenu(account, profiles));
            }

            label(t("Media library and YouTube", "Медиатека и YouTube"));
            cell(t("Route", "Маршрут"), mediaSummary(), () -> chooseMedia(profiles));
            cell(t("Check YouTube and Telegram", "Проверить YouTube и Telegram"), t("Shows where the connection breaks", "Покажет, на каком маршруте обрыв"), this::diagnose);

            label(t("Keys", "Ключи") + " · " + profiles.size() + " / " + XrayConfig.MAX_PROFILES);
            cell(t("Add: subscription or links", "Добавить: подписка или ссылки"), t("Paste a subscription URL, vless:// links or Xray JSON", "Ссылка подписки, ключи vless:// или Xray JSON"), this::importDialog);
            cell(t("Check all keys now", "Проверить все ключи сейчас"), t("Auto mode picks the fastest and rotates on failure", "Режим «Авто» выбирает быстрейший и меняет при сбое"), () -> {
                toast(t("Checking…", "Проверяю…"));
                XrayRuntime.probeNow(this::render);
            });
            String activeId = XrayRuntime.autoActiveId();
            for (XrayProfile p : profiles) {
                int ms = XrayRuntime.latency(p.id);
                String state = ms > 0 ? ms + " " + t("ms", "мс") : ms < 0 ? t("no response", "не отвечает") : "";
                String used = usedBy(p.id);
                String value = p.outbound.optString("protocol").toUpperCase(Locale.ROOT) + (state.isEmpty() ? "" : " · " + state) + (used.isEmpty() ? "" : " · " + used);
                cell((p.id.equals(activeId) ? "● " : "") + p.name, value, () -> keyMenu(p));
            }

            List<String[]> subs = XrayProfileStore.subscriptions();
            if (!subs.isEmpty()) {
                label(t("Subscriptions", "Подписки"));
                for (String[] sub : subs) {
                    int count = 0;
                    for (XrayProfile p : profiles) if (sub[0].equals(p.sub)) count++;
                    final int keys = count;
                    cell(hostOf(sub[1]), keys + " " + t("keys · tap to update", "ключей · нажмите, чтобы обновить"), () -> subscriptionMenu(sub, keys));
                }
            }
            label(t("All routes are local loopback listeners. An unavailable chosen key stops the connection instead of switching to direct. Calls and embedded web pages do not use the route.",
                    "Все маршруты идут через локальные порты. Если выбранный ключ недоступен, соединение останавливается и не переключается на прямое. Звонки и встроенные веб-страницы маршрут не используют."));
        } catch (Exception e) {
            label(t("The device key cannot open the restored profiles. Reset this local list, then import your profile links again.", "Ключ устройства не открывает восстановленные профили. Сбросьте локальный список, затем импортируйте ссылки профилей заново."));
            cell(t("Reset unreadable Xray profiles", "Сбросить нечитаемые профили Xray"), "", this::resetUnreadable);
        }
    }

    private String stateName(int state) {
        switch (state) {
            case ConnectionsManager.ConnectionStateConnected: return t("connected", "подключено");
            case ConnectionsManager.ConnectionStateUpdating: return t("updating", "обновление");
            case ConnectionsManager.ConnectionStateConnecting: return t("connecting…", "подключение…");
            case ConnectionsManager.ConnectionStateConnectingToProxy: return t("connecting to proxy…", "подключение к прокси…");
            case ConnectionsManager.ConnectionStateWaitingForNetwork: return t("waiting for network", "ожидание сети");
            default: return "";
        }
    }

    private String accountSummary(int account) throws Exception {
        AccountNetworkController controller = AccountNetworkController.getInstance();
        AccountNetworkProfile route = controller.getProfile(account);
        String summary = route != null && route.mode == AccountNetworkProfile.MODE_XRAY ? "Xray · " + keyName(route.xrayProfileId) : controller.getSummary(account);
        String state = stateName(controller.getConnectionState(account));
        int ping = controller.getPing(account);
        return summary + (state.isEmpty() ? "" : " · " + state) + (ping > 0 ? " · " + ping + " " + t("ms", "мс") : "");
    }

    private String mediaSummary() throws Exception {
        String route = XrayProfileStore.mediaRoute();
        if (route.isEmpty()) return t("Direct", "Напрямую");
        if (route.equals("account")) return t("Xray of the current account", "Xray текущего аккаунта");
        return "Xray · " + keyName(route);
    }

    private String keyName(String id) throws Exception {
        if (XrayRuntime.AUTO.equals(id)) {
            String a = XrayRuntime.autoActiveId();
            XrayProfile ap = a.isEmpty() ? null : XrayProfileStore.find(a);
            return t("Auto", "Авто") + (ap == null ? "" : " · " + ap.name);
        }
        XrayProfile p = XrayProfileStore.find(id);
        return p == null ? t("unavailable", "недоступен") : p.name;
    }

    private String usedBy(String id) {
        StringBuilder sb = new StringBuilder();
        try {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) if (UserConfig.getInstance(a).isClientActivated()) {
                AccountNetworkProfile r = AccountNetworkController.getInstance().getProfile(a);
                if (r != null && r.mode == AccountNetworkProfile.MODE_XRAY && id.equals(r.xrayProfileId)) { if (sb.length() > 0) sb.append(", "); sb.append("#").append(a + 1); }
            }
            if (id.equals(XrayProfileStore.mediaRoute())) { if (sb.length() > 0) sb.append(", "); sb.append(t("media", "медиа")); }
        } catch (Exception ignored) { }
        return sb.toString();
    }

    private String hostOf(String url) {
        try { String h = android.net.Uri.parse(url).getHost(); return h == null ? url : h; } catch (Exception e) { return url; }
    }

    // ------------------------------------------------------------------ account route

    private void accountMenu(int account, List<XrayProfile> profiles) {
        ArrayList<String> names = new ArrayList<>();
        names.add(t("Global route", "Общий маршрут"));
        names.add(t("Direct", "Напрямую"));
        names.add(t("Auto: best of all keys", "Авто: лучший из всех ключей"));
        for (XrayProfile p : profiles) names.add(p.name);
        names.add(t("MTProto / SOCKS5 and details…", "MTProto / SOCKS5 и детали…"));
        names.add(t("Invisible mode…", "Невидимка…"));
        final int extra = names.size() - 2;
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle(t("Account route", "Маршрут аккаунта")).setItems(names.toArray(new String[0]), (d, which) -> {
            if (which == extra) { presentFragment(new TelePremiumNetworkProfileActivity(account)); return; }
            if (which == extra + 1) { presentFragment(new TelePremiumGhostActivity(account)); return; }
            if (which == 0) AccountNetworkController.getInstance().resetToGlobal(account);
            else {
                AccountNetworkProfile route = AccountNetworkController.getInstance().getEditableProfile(account);
                route.mode = which == 1 ? AccountNetworkProfile.MODE_DIRECT : AccountNetworkProfile.MODE_XRAY;
                if (route.mode == AccountNetworkProfile.MODE_XRAY) route.ipStrategy = ConnectionsManager.USE_IPV4_ONLY;
                if (which == 2) { route.xrayProfileId = XrayRuntime.AUTO; route.displayName = t("Auto", "Авто"); }
                else if (which > 2) { XrayProfile p = profiles.get(which - 3); route.xrayProfileId = p.id; route.displayName = p.name; }
                AccountNetworkController.getInstance().saveAndApply(account, route);
            }
            XrayRuntime.refresh();
            render();
        }).create());
    }

    private void chooseMedia(List<XrayProfile> profiles) {
        ArrayList<String> names = new ArrayList<>();
        names.add(t("Direct", "Напрямую"));
        names.add(t("Xray of the current account", "Xray текущего аккаунта"));
        names.add(t("Auto: best of all keys", "Авто: лучший из всех ключей"));
        for (XrayProfile p : profiles) names.add(p.name);
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle(t("Media route", "Маршрут медиатеки")).setItems(names.toArray(new String[0]), (d, which) -> {
            try {
                XrayProfileStore.setMediaRoute(which == 0 ? "" : which == 1 ? "account" : which == 2 ? XrayRuntime.AUTO : profiles.get(which - 3).id);
                XrayRuntime.refresh();
                org.telegram.messenger.telepremium.media.TelePremiumPlaybackController.networkRouteChanged();
                render();
            } catch (Exception e) { error(t("Could not save route", "Не удалось сохранить маршрут")); }
        }).create());
    }

    // ------------------------------------------------------------------ key menu

    private void keyMenu(XrayProfile p) {
        String[] items = {
                t("Use for an account…", "Назначить аккаунту…"),
                t("Use for media and YouTube", "Назначить медиатеке и YouTube"),
                t("Rename", "Переименовать"),
                p.bypassLan ? t("Route local networks through the key", "Локальные сети через ключ") : t("Bypass local networks", "Локальные сети напрямую"),
                t("Delete", "Удалить")};
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle(p.name).setItems(items, (d, which) -> {
            if (which == 0) pickAccountFor(p);
            else if (which == 1) {
                try { XrayProfileStore.setMediaRoute(p.id); XrayRuntime.refresh(); org.telegram.messenger.telepremium.media.TelePremiumPlaybackController.networkRouteChanged(); render(); }
                catch (Exception e) { error(t("Could not save route", "Не удалось сохранить маршрут")); }
            } else if (which == 2) {
                EditText input = new EditText(getParentActivity());
                input.setSingleLine(true);
                input.setText(p.name);
                input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
                showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle(t("Key name", "Имя ключа")).setView(input)
                        .setPositiveButton(t("Save", "Сохранить"), (a, b) -> { String n = input.getText().toString().trim(); if (!n.isEmpty()) { p.name = n; update(p, false); } })
                        .setNegativeButton(t("Cancel", "Отмена"), null).create());
            } else if (which == 3) { p.bypassLan = !p.bypassLan; update(p, false); }
            else update(p, true);
        }).create());
    }

    private void pickAccountFor(XrayProfile p) {
        ArrayList<String> names = new ArrayList<>();
        final ArrayList<Integer> ids = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) if (UserConfig.getInstance(a).isClientActivated()) {
            names.add(UserObject.getUserName(UserConfig.getInstance(a).getCurrentUser()));
            ids.add(a);
        }
        if (names.isEmpty()) return;
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle(p.name).setItems(names.toArray(new String[0]), (d, which) -> {
            int account = ids.get(which);
            AccountNetworkProfile route = AccountNetworkController.getInstance().getEditableProfile(account);
            route.mode = AccountNetworkProfile.MODE_XRAY;
            route.xrayProfileId = p.id;
            route.displayName = p.name;
            route.ipStrategy = ConnectionsManager.USE_IPV4_ONLY;
            AccountNetworkController.getInstance().saveAndApply(account, route);
            XrayRuntime.refresh();
            render();
        }).create());
    }

    private void update(XrayProfile profile, boolean remove) {
        try {
            if (remove) {
                if (profile.id.equals(XrayProfileStore.mediaRoute())) throw new IllegalArgumentException(t("Select another media route first", "Сначала выберите другой маршрут медиатеки"));
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    AccountNetworkProfile r = AccountNetworkStore.getInstance().load(a);
                    if (r != null && r.mode == AccountNetworkProfile.MODE_XRAY && r.xrayProfileId.equals(profile.id)) throw new IllegalArgumentException(t("Assign another route to accounts using this key first", "Сначала измените маршрут аккаунтов, использующих этот ключ"));
                }
            }
            List<XrayProfile> all = XrayProfileStore.profiles();
            for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(profile.id)) { if (remove) all.remove(i); else all.set(i, profile); break; }
            XrayProfileStore.saveAll(all);
            XrayRuntime.refresh();
            render();
        } catch (Exception e) { error(e instanceof IllegalArgumentException ? e.getMessage() : t("Could not save profiles", "Не удалось сохранить профили")); }
    }

    // ------------------------------------------------------------------ import and subscriptions

    private void importDialog() {
        EditText input = new EditText(getParentActivity());
        input.setHint(t("Subscription https://… · vless:// · vmess:// · trojan:// · ss:// · Xray JSON", "Подписка https://… · vless:// · vmess:// · trojan:// · ss:// · Xray JSON"));
        input.setMinLines(4);
        input.setMaxLines(12);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(262144)});
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence clip = cm.getPrimaryClip().getItemAt(0).coerceToText(getParentActivity());
                String low = clip == null ? "" : clip.toString().trim().toLowerCase(Locale.ROOT);
                if (low.startsWith("vless://") || low.startsWith("vmess://") || low.startsWith("trojan://") || low.startsWith("ss://") || low.startsWith("hysteria2://") || low.startsWith("{") || (low.startsWith("http") && low.indexOf(' ') < 0 && low.indexOf('\n') < 0)) {
                    input.setText(clip.toString().trim());
                    input.setSelection(input.length());
                }
            }
        } catch (Throwable ignored) { }
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle(t("Import: subscription or links", "Импорт: подписка или ссылки"))
                .setView(input).setPositiveButton(t("Import", "Импортировать"), (d, w) -> {
                    String raw = input.getText().toString().trim();
                    input.setText("");
                    toast(t("Importing…", "Импортирую…"));
                    XrayRuntime.importProfiles(raw, result -> {
                        if (result != null) error(result); else toast(XrayRuntime.lastImportSummary());
                        render();
                    });
                }).setNegativeButton(t("Cancel", "Отмена"), null).create());
    }

    private void subscriptionMenu(String[] sub, int keys) {
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle(hostOf(sub[1])).setItems(new String[]{t("Update keys", "Обновить ключи"), t("Delete subscription and its keys", "Удалить подписку и её ключи")}, (d, which) -> {
            if (which == 0) {
                toast(t("Updating…", "Обновляю…"));
                XrayRuntime.refreshSubscription(sub[0], result -> { if (result != null) error(result); else toast(XrayRuntime.lastImportSummary()); render(); });
            } else removeSubscription(sub[0]);
        }).create());
    }

    private void removeSubscription(String id) {
        try {
            List<XrayProfile> all = XrayProfileStore.profiles();
            List<XrayProfile> keep = new ArrayList<>();
            for (XrayProfile p : all) {
                if (!id.equals(p.sub)) { keep.add(p); continue; }
                if (p.id.equals(XrayProfileStore.mediaRoute())) throw new IllegalArgumentException(t("Select another media route first", "Сначала выберите другой маршрут медиатеки"));
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    AccountNetworkProfile r = AccountNetworkStore.getInstance().load(a);
                    if (r != null && r.mode == AccountNetworkProfile.MODE_XRAY && r.xrayProfileId.equals(p.id)) throw new IllegalArgumentException(t("Assign another route to accounts using these keys first", "Сначала измените маршрут аккаунтов, использующих эти ключи"));
                }
            }
            XrayProfileStore.saveAll(keep);
            XrayProfileStore.removeSubscription(id);
            XrayRuntime.refresh();
            render();
        } catch (Exception e) { error(e instanceof IllegalArgumentException ? e.getMessage() : t("Could not delete", "Не удалось удалить")); }
    }

    private void resetUnreadable() {
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(t("Reset local Xray profiles?", "Сбросить локальные профили Xray?"))
                .setMessage(t("Only this device's proxy list is reset. Media will use Direct; accounts assigned to missing profiles remain offline until you choose new routes.", "Сбрасывается только локальный список прокси. Медиатека перейдёт на прямое соединение; аккаунты со старыми профилями останутся без соединения, пока вы не назначите новые маршруты."))
                .setPositiveButton(t("Reset profiles", "Сбросить профили"), (d, w) -> {
                    try { XrayProfileStore.resetAfterKeyLoss(); XrayRuntime.refresh(); org.telegram.messenger.telepremium.media.TelePremiumPlaybackController.networkRouteChanged(); render(); }
                    catch (Exception e) { error(t("Could not reset profiles", "Не удалось сбросить профили")); }
                }).setNegativeButton(t("Cancel", "Отмена"), null).create());
    }

    // ------------------------------------------------------------------ diagnostics

    private static String probe(String address, Proxy proxy) {
        HttpURLConnection c = null;
        long t0 = System.nanoTime();
        try {
            c = (HttpURLConnection) new URL(address).openConnection(proxy);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setUseCaches(false);
            int code = c.getResponseCode();
            return "✓ " + (System.nanoTime() - t0) / 1000000 + " ms · HTTP " + code;
        } catch (Throwable e) {
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return "✗ " + (m.length() > 70 ? m.substring(0, 70) : m);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void diagnose() {
        toast(t("Checking the connection…", "Проверяю соединение…"));
        final String[][] targets = {{"YouTube", "https://www.youtube.com/generate_204"}, {t("YouTube video", "Видео YouTube"), "https://i.ytimg.com/generate_204"}, {"Telegram", "https://api.telegram.org/"}};
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            String routeName;
            Proxy media = Proxy.NO_PROXY;
            try { routeName = mediaSummary(); media = XrayRuntime.mediaProxy(); } catch (Throwable e) { routeName = t("unavailable", "недоступен"); media = null; }
            sb.append(t("Media route: ", "Маршрут медиатеки: ")).append(routeName).append("\n");
            for (String[] target : targets) sb.append(target[0]).append(": ").append(media == null ? "✗ " + t("route unavailable", "маршрут недоступен") : probe(target[1], media)).append("\n");
            sb.append("\n").append(t("Direct, for comparison:", "Напрямую, для сравнения:")).append("\n");
            for (String[] target : targets) sb.append(target[0]).append(": ").append(probe(target[1], Proxy.NO_PROXY)).append("\n");
            final String text = sb.toString().trim();
            AndroidUtilities.runOnUIThread(() -> { if (getParentActivity() != null) error(text); });
        }, "tp-conn-diagnose").start();
    }
}
