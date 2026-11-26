package org.telegram.messenger.telepremium;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.telepremium.xray.XrayProfile;
import org.telegram.messenger.telepremium.xray.XrayProfileStore;
import org.telegram.tgnet.TLRPC;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Export of authorised accounts (auth keys + account state) to one encrypted file and
 * restore from it. A restore is staged first and applied on the next process start,
 * before any Telegram component reads its files or preferences.
 */
public final class TelePremiumSessionBackup {
    private TelePremiumSessionBackup() {}

    public static final class Account {
        public int src;
        public long userId;
        public String name = "", phone = "", username = "";
        public int targetSlot = -1;
        public boolean replaces;
    }

    public static final class Opened {
        public Map<String, byte[]> files;
        public List<Account> accounts = new ArrayList<>();
        public boolean hasXray;
        public long created;
    }

    private static final Set<String> USER_KEYS = new HashSet<>(Arrays.asList(
            "2dialogsLoadOffsetAccess", "2dialogsLoadOffsetChannelId", "2dialogsLoadOffsetChatId", "2dialogsLoadOffsetDate", "2dialogsLoadOffsetId", "2dialogsLoadOffsetUserId",
            "6migrateOffsetAccess", "6migrateOffsetChannelId", "6migrateOffsetChatId", "6migrateOffsetDate", "6migrateOffsetId", "6migrateOffsetUserId",
            "autoDownloadConfigLoadTime", "botGuestRatingLoadTime", "botRatingLoadTime", "contactsReimported", "contactsSavedCount", "draftsLoaded", "filtersLoaded",
            "genericAnimationsStickerPack", "hasSecureData", "hasValidDialogLoadIds", "lastBroadcastId", "lastContactsSyncTime", "lastHintsSyncTime", "lastMyLocationShareTime",
            "lastSendMessageId", "lastUpdatedGenericAnimations", "lastUpdatedPremiumGiftsStickerPack", "loginTime", "notificationsSettingsLoaded4", "notificationsSignUpSettingsLoaded",
            "premiumGiftsStickerPack", "ratingLoadTime", "sharingMyLocationUntil", "showCallsTab", "suggestContacts", "syncContacts", "terms", "tmpPassword",
            "unreadDialogsLoaded", "user", "webappRatingLoadTime"));

    private static Context ctx() { return ApplicationLoader.applicationContext; }
    private static File accountDir(int slot) {
        File files = ApplicationLoader.getFilesDirFixed();
        return slot == 0 ? files : new File(files, "account" + slot);
    }
    private static SharedPreferences prefs(String name) { return ctx().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private static String userPrefsName(int slot) { return slot == 0 ? "userconfing" : "userconfig" + slot; }
    private static String notificationsName(int slot) { return "Notifications" + (slot == 0 ? "" : String.valueOf(slot)); }
    private static String mainName(int slot) { return "mainconfig" + slot; }
    private static String networkName(long userId) { return "telepremium_network_account_u" + userId; }

    public static List<Account> activeAccounts() {
        ArrayList<Account> out = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (!config.isClientActivated()) continue;
            Account acc = new Account();
            acc.src = a;
            acc.userId = config.getClientUserId();
            TLRPC.User user = config.getCurrentUser();
            if (user != null) {
                acc.name = UserObject.getUserName(user);
                acc.phone = user.phone == null ? "" : user.phone;
                acc.username = UserObject.getPublicUsername(user) == null ? "" : UserObject.getPublicUsername(user);
            }
            out.add(acc);
        }
        return out;
    }

    // ---- preference (de)serialisation ----
    private static JSONObject dump(SharedPreferences p, Set<String> only) throws Exception {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            String k = e.getKey();
            if (only != null && !only.contains(k)) continue;
            Object v = e.getValue();
            JSONObject o = new JSONObject();
            if (v instanceof String) o.put("t", "s").put("v", (String) v);
            else if (v instanceof Integer) o.put("t", "i").put("v", ((Integer) v).intValue());
            else if (v instanceof Long) o.put("t", "l").put("v", ((Long) v).longValue());
            else if (v instanceof Boolean) o.put("t", "b").put("v", ((Boolean) v).booleanValue());
            else if (v instanceof Float) o.put("t", "f").put("v", ((Float) v).doubleValue());
            else if (v instanceof Set) {
                JSONArray arr = new JSONArray();
                for (Object s : (Set<?>) v) arr.put(String.valueOf(s));
                o.put("t", "ss").put("v", arr);
            } else continue;
            out.put(k, o);
        }
        return out;
    }

    private static void load(SharedPreferences p, JSONObject data, Set<String> only, boolean clearOthers) throws Exception {
        SharedPreferences.Editor ed = p.edit();
        if (clearOthers) {
            if (only == null) ed.clear();
            else for (String k : only) ed.remove(k);
        }
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (only != null && !only.contains(k)) continue;
            JSONObject o = data.getJSONObject(k);
            switch (o.getString("t")) {
                case "s": ed.putString(k, o.getString("v")); break;
                case "i": ed.putInt(k, o.getInt("v")); break;
                case "l": ed.putLong(k, o.getLong("v")); break;
                case "b": ed.putBoolean(k, o.getBoolean("v")); break;
                case "f": ed.putFloat(k, (float) o.getDouble("v")); break;
                case "ss":
                    JSONArray arr = o.getJSONArray("v");
                    HashSet<String> set = new HashSet<>();
                    for (int i = 0; i < arr.length(); i++) set.add(arr.getString(i));
                    ed.putStringSet(k, set);
                    break;
                default:
            }
        }
        if (!ed.commit()) throw new IllegalStateException("Could not write settings");
    }

    private static byte[] read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void write(File f, byte[] data) throws Exception {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
            out.getFD().sync();
        }
    }

    private static byte[] json(JSONObject o) { return o.toString().getBytes(StandardCharsets.UTF_8); }

    // ---- export ----
    public static byte[] export(List<Integer> slots, boolean includeXray, char[] password) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        JSONArray accounts = new JSONArray();
        for (int slot : slots) {
            UserConfig config = UserConfig.getInstance(slot);
            if (!config.isClientActivated()) continue;
            long uid = config.getClientUserId();
            String base = "acc" + slot + "/";
            File dir = accountDir(slot);
            File[] list = dir.listFiles();
            boolean hasNative = false;
            if (list != null) for (File f : list) {
                if (f.isFile() && f.getName().startsWith("tgnet") && f.length() > 0 && f.length() <= 4 * 1024 * 1024) {
                    files.put(base + "native/" + f.getName(), read(f));
                    if (f.getName().equals("tgnet.dat")) hasNative = true;
                }
            }
            if (!hasNative) throw new IllegalStateException("NO_SESSION_FILE");
            files.put(base + "prefs_user.json", json(dump(prefs(userPrefsName(slot)), slot == 0 ? USER_KEYS : null)));
            files.put(base + "prefs_notifications.json", json(dump(prefs(notificationsName(slot)), null)));
            if (slot > 0) files.put(base + "prefs_main.json", json(dump(prefs(mainName(slot)), null)));
            files.put(base + "tp_network.json", json(dump(prefs(networkName(uid)), null)));
            files.put(base + "tp_ghost.json", json(new JSONObject().put("flags", TelePremiumGhost.flags(slot))));
            TLRPC.User user = config.getCurrentUser();
            JSONObject info = new JSONObject().put("src", slot).put("userId", uid)
                    .put("name", user == null ? "" : UserObject.getUserName(user))
                    .put("phone", user == null || user.phone == null ? "" : user.phone)
                    .put("username", user == null || UserObject.getPublicUsername(user) == null ? "" : UserObject.getPublicUsername(user));
            accounts.put(info);
        }
        if (accounts.length() == 0) throw new IllegalStateException("NO_ACCOUNTS");
        boolean xray = false;
        if (includeXray) {
            try {
                JSONArray arr = new JSONArray();
                for (XrayProfile p : XrayProfileStore.profiles()) arr.put(p.toJson());
                if (arr.length() > 0) {
                    files.put("xray.json", arr.toString().getBytes(StandardCharsets.UTF_8));
                    xray = true;
                }
            } catch (Exception ignored) {
            }
        }
        files.put("manifest.json", json(new JSONObject().put("v", 1).put("created", System.currentTimeMillis())
                .put("accounts", accounts).put("xray", xray)));
        byte[] zip = SessionCrypto.pack(files);
        return SessionCrypto.encrypt(zip, password);
    }

    // ---- import ----
    public static Opened open(byte[] file, char[] password) throws Exception {
        Map<String, byte[]> files = SessionCrypto.unpack(SessionCrypto.decrypt(file, password));
        byte[] m = files.get("manifest.json");
        if (m == null) throw new IllegalArgumentException("Not a session file");
        JSONObject manifest = new JSONObject(new String(m, StandardCharsets.UTF_8));
        if (manifest.optInt("v") != 1) throw new IllegalArgumentException("Unsupported version");
        Opened out = new Opened();
        out.files = files;
        out.created = manifest.optLong("created");
        out.hasXray = manifest.optBoolean("xray") && files.containsKey("xray.json");
        JSONArray arr = manifest.getJSONArray("accounts");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Account a = new Account();
            a.src = o.getInt("src");
            a.userId = o.getLong("userId");
            a.name = o.optString("name");
            a.phone = o.optString("phone");
            a.username = o.optString("username");
            if (!files.containsKey("acc" + a.src + "/native/tgnet.dat")) continue;
            out.accounts.add(a);
        }
        if (out.accounts.isEmpty()) throw new IllegalArgumentException("No sessions in file");
        assignSlots(out.accounts);
        return out;
    }

    /** An account that is already signed in keeps its slot (and is replaced); others take free slots. */
    public static void assignSlots(List<Account> accounts) {
        boolean[] used = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
        for (Account a : accounts) {
            a.targetSlot = -1;
            a.replaces = false;
            for (int s = 0; s < UserConfig.MAX_ACCOUNT_COUNT; s++) {
                if (!used[s] && UserConfig.getInstance(s).isClientActivated() && UserConfig.getInstance(s).getClientUserId() == a.userId) {
                    a.targetSlot = s;
                    a.replaces = true;
                    used[s] = true;
                    break;
                }
            }
        }
        for (Account a : accounts) {
            if (a.targetSlot >= 0) continue;
            for (int s = 0; s < UserConfig.MAX_ACCOUNT_COUNT; s++) {
                if (!used[s] && !UserConfig.getInstance(s).isClientActivated()) {
                    a.targetSlot = s;
                    used[s] = true;
                    break;
                }
            }
        }
    }

    private static File pendingDir() { return new File(ApplicationLoader.getFilesDirFixed(), "tp_import_pending"); }

    /** Stages the restore; the caller must restart the process afterwards. */
    public static void stage(Opened opened) throws Exception {
        JSONArray plan = new JSONArray();
        for (Account a : opened.accounts) {
            if (a.targetSlot < 0) continue;
            plan.put(new JSONObject().put("src", a.src).put("slot", a.targetSlot).put("userId", a.userId));
        }
        if (plan.length() == 0) throw new IllegalStateException("NO_FREE_SLOT");
        File dir = pendingDir();
        dir.mkdirs();
        write(new File(dir, "pending.zip"), SessionCrypto.pack(opened.files));
        write(new File(dir, "plan.json"), new JSONObject().put("accounts", plan).put("xray", opened.hasXray).toString().getBytes(StandardCharsets.UTF_8));
    }

    public static boolean hasPending() {
        return new File(pendingDir(), "plan.json").exists();
    }

    /** Called from Application.onCreate before Telegram reads its state. Never throws. */
    public static void applyPending(Context context) {
        File dir;
        try {
            dir = new File(context.getFilesDir(), "tp_import_pending");
        } catch (Throwable t) {
            return;
        }
        File planFile = new File(dir, "plan.json"), zipFile = new File(dir, "pending.zip");
        if (!planFile.exists() || !zipFile.exists()) {
            deleteQuietly(dir);
            return;
        }
        try {
            JSONObject plan = new JSONObject(new String(read(planFile), StandardCharsets.UTF_8));
            Map<String, byte[]> files = SessionCrypto.unpack(read(zipFile));
            JSONArray accounts = plan.getJSONArray("accounts");
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject o = accounts.getJSONObject(i);
                int src = o.getInt("src"), slot = o.getInt("slot");
                long uid = o.getLong("userId");
                if (slot < 0 || slot >= UserConfig.MAX_ACCOUNT_COUNT) continue;
                String base = "acc" + src + "/";
                File target = accountDir(slot);
                target.mkdirs();
                File[] old = target.listFiles();
                if (old != null) for (File f : old) {
                    String n = f.getName();
                    if (f.isFile() && (n.startsWith("tgnet") || n.startsWith("cache4.db"))) f.delete();
                }
                for (Map.Entry<String, byte[]> e : files.entrySet()) {
                    String key = e.getKey();
                    if (!key.startsWith(base + "native/")) continue;
                    String name = key.substring((base + "native/").length());
                    if (name.isEmpty() || name.contains("/") || !name.startsWith("tgnet")) continue;
                    write(new File(target, name), e.getValue());
                }
                byte[] u = files.get(base + "prefs_user.json");
                if (u != null) load(prefs(userPrefsName(slot)), new JSONObject(new String(u, StandardCharsets.UTF_8)), slot == 0 ? USER_KEYS : null, true);
                byte[] n = files.get(base + "prefs_notifications.json");
                if (n != null) load(prefs(notificationsName(slot)), new JSONObject(new String(n, StandardCharsets.UTF_8)), null, true);
                byte[] m = files.get(base + "prefs_main.json");
                if (m != null && slot > 0) load(prefs(mainName(slot)), new JSONObject(new String(m, StandardCharsets.UTF_8)), null, true);
                byte[] net = files.get(base + "tp_network.json");
                if (net != null && uid > 0) load(prefs(networkName(uid)), new JSONObject(new String(net, StandardCharsets.UTF_8)), null, true);
                byte[] g = files.get(base + "tp_ghost.json");
                if (g != null && uid > 0) {
                    int flags = new JSONObject(new String(g, StandardCharsets.UTF_8)).optInt("flags");
                    prefs("telepremium_ghost").edit().putInt("u" + uid, flags).commit();
                }
                // The push token of the old device is useless: force re-registration.
                prefs(userPrefsName(slot)).edit().putBoolean("registeredForPush", false).commit();
            }
            byte[] x = files.get("xray.json");
            if (plan.optBoolean("xray") && x != null) {
                try {
                    JSONArray arr = new JSONArray(new String(x, StandardCharsets.UTF_8));
                    List<XrayProfile> current = XrayProfileStore.profiles();
                    HashSet<String> ids = new HashSet<>();
                    for (XrayProfile p : current) ids.add(p.id);
                    for (int i = 0; i < arr.length() && current.size() < org.telegram.messenger.telepremium.xray.XrayConfig.MAX_PROFILES; i++) {
                        XrayProfile p = XrayProfile.fromJson(arr.getJSONObject(i));
                        if (ids.add(p.id)) current.add(p);
                    }
                    XrayProfileStore.saveAll(current);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            // A failed restore must never prevent the app from starting.
        } finally {
            deleteQuietly(dir);
        }
    }

    private static void deleteQuietly(File dir) {
        try {
            File[] list = dir.listFiles();
            if (list != null) for (File f : list) f.delete();
            dir.delete();
        } catch (Throwable ignored) {
        }
    }
}
