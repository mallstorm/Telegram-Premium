package org.telegram.messenger.telepremium.cloud;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** A frozen, reviewed set of account/channel identities. Never expanded during deletion. */
public final class TelePremiumCloudResetPlan {
    public static final int PENDING = 0, DELETED = 1, ABSENT = 2;
    public final String id;
    public final long createdAt;
    public final ArrayList<Account> accounts = new ArrayList<>();
    public final ArrayList<Target> targets = new ArrayList<>();
    public final Set<String> observedChannels = new HashSet<>(); // transient read-only scan evidence
    public final Set<Long> readyUsers = new HashSet<>();
    public int ignoredChannels;
    public String phase = TelePremiumCloudResetState.DELETING;

    public static final class Account {
        public final int slot;
        public final long userId;
        public final String name;
        public Account(int slot, long userId, String name) { this.slot = slot; this.userId = userId; this.name = name; }
    }

    public static final class Target {
        public final long userId, channelId, accessHash;
        public final String title, about;
        public final boolean knownBinding;
        public int state;
        public Target(long userId, long channelId, long accessHash, String title, String about, boolean knownBinding) {
            if (userId <= 0 || channelId <= 0 || accessHash == 0) throw new IllegalArgumentException("Invalid reset target");
            this.userId = userId; this.channelId = channelId; this.accessHash = accessHash;
            this.title = title; this.about = about == null ? "" : about; this.knownBinding = knownBinding;
        }
        public String identity() { return userId + ":" + channelId; }
    }

    public TelePremiumCloudResetPlan() { this(UUID.randomUUID().toString(), System.currentTimeMillis()); }
    private TelePremiumCloudResetPlan(String id, long createdAt) { this.id = UUID.fromString(id).toString(); this.createdAt = createdAt; }

    public static boolean isManaged(boolean creator, boolean broadcast, boolean megagroup, String about, boolean knownBinding) {
        if (!creator || !broadcast || megagroup) return false;
        String marker = about == null ? "" : about.trim();
        return knownBinding || "TELEPREMIUM_CLOUD_STORAGE_V2".equals(marker)
                || "TP_VAULT_V3".equals(marker) || marker.startsWith("TP_VAULT_V3:");
    }

    public int deletedCount() { int n = 0; for (Target t : targets) if (t.state == DELETED) n++; return n; }
    public int absentCount() { int n = 0; for (Target t : targets) if (t.state == ABSENT) n++; return n; }
    public boolean deletionComplete() { for (Target t : targets) if (t.state == PENDING) return false; return true; }
    public String name(long userId) { for (Account a : accounts) if (a.userId == userId) return a.name; return Long.toString(userId); }

    public String encode() throws Exception {
        JSONObject o = new JSONObject(); o.put("version", 1); o.put("id", id); o.put("createdAt", createdAt);
        o.put("phase", phase); o.put("ignored", ignoredChannels);
        JSONArray a = new JSONArray(), t = new JSONArray(), ready = new JSONArray();
        for (Account x : accounts) a.put(new JSONObject().put("slot", x.slot).put("user", x.userId).put("name", x.name));
        for (Target x : targets) t.put(new JSONObject().put("user", x.userId).put("channel", x.channelId)
                .put("hash", x.accessHash).put("title", x.title).put("about", x.about).put("known", x.knownBinding).put("state", x.state));
        for (long user : readyUsers) ready.put(user);
        o.put("accounts", a); o.put("targets", t); o.put("ready", ready); return o.toString();
    }

    public static TelePremiumCloudResetPlan decode(String raw) throws Exception {
        JSONObject o = new JSONObject(raw);
        if (o.getInt("version") != 1) throw new IllegalArgumentException("Unsupported reset plan");
        TelePremiumCloudResetPlan p = new TelePremiumCloudResetPlan(o.getString("id"), o.getLong("createdAt"));
        p.phase = o.getString("phase"); p.ignoredChannels = o.optInt("ignored");
        if (!TelePremiumCloudResetState.DELETING.equals(p.phase) && !TelePremiumCloudResetState.CLEARING.equals(p.phase)
                && !TelePremiumCloudResetState.CREATING.equals(p.phase)) throw new IllegalArgumentException("Invalid reset phase");
        JSONArray a = o.getJSONArray("accounts"), t = o.getJSONArray("targets"), ready = o.getJSONArray("ready");
        Set<Long> users = new HashSet<>(); Set<String> targets = new HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i); Account account = new Account(x.getInt("slot"), x.getLong("user"), x.getString("name"));
            if (account.userId <= 0 || account.slot < 0) throw new IllegalArgumentException("Invalid account");
            p.accounts.add(account); users.add(account.userId);
        }
        if (users.isEmpty()) throw new IllegalArgumentException("Empty reset scope");
        for (int i = 0; i < t.length(); i++) {
            JSONObject x = t.getJSONObject(i);
            Target target = new Target(x.getLong("user"), x.getLong("channel"), x.getLong("hash"), x.getString("title"), x.optString("about"), x.optBoolean("known"));
            target.state = x.getInt("state");
            if (!users.contains(target.userId) || !targets.add(target.identity()) || target.state < PENDING || target.state > ABSENT)
                throw new IllegalArgumentException("Invalid reset target scope");
            p.targets.add(target);
        }
        for (int i = 0; i < ready.length(); i++) {
            long user = ready.getLong(i); if (!users.contains(user)) throw new IllegalArgumentException("Invalid ready account");
            p.readyUsers.add(user);
        }
        return p;
    }
}
