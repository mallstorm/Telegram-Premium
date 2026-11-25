package org.telegram.messenger.telepremium.aicenter;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.security.keystore.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import org.json.*;
import org.telegram.messenger.*;

/**
 * Each account has an independent Keystore key. All content including source names and TL handles
 * is AEAD encrypted.
 */
public final class CenterStore extends SQLiteOpenHelper {
  public static final ExecutorService IO =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "tp-ai-center-index"));
  private static CenterStore instance;
  private long cachedBytes = -1;
  private final Map<String, SecretKey> dataKeys = new HashMap<>();
  private final Map<String, SecretKey> keyCache = new HashMap<>();

  public static synchronized CenterStore get() {
    if (instance == null) instance = new CenterStore();
    return instance;
  }

  private CenterStore() {
    super(ApplicationLoader.applicationContext, "tp_ai_center_v1.db", null, 2);
  }

  @Override
  public void onConfigure(SQLiteDatabase db) {
    db.execSQL("PRAGMA secure_delete=ON");
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE records (account INTEGER NOT NULL, owner INTEGER NOT NULL, kind TEXT NOT"
            + " NULL, id TEXT NOT NULL, value BLOB NOT NULL, v INTEGER NOT NULL DEFAULT 2, stamp"
            + " INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(account,owner,kind,id))");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int old, int now) {
    if (old == 1 && now == 2) {
      db.execSQL("ALTER TABLE records ADD COLUMN v INTEGER NOT NULL DEFAULT 1");
      db.execSQL("ALTER TABLE records ADD COLUMN stamp INTEGER NOT NULL DEFAULT 0");
    } else throw new IllegalStateException("Unsupported index migration");
  }

  private String alias(int account, long owner) {
    return "tp_aic_" + account + "_" + owner;
  }

  private SecretKey wrappingKey(int account, long owner) throws Exception {
    String a = alias(account, owner);
    if (keyCache.containsKey(a)) return keyCache.get(a);
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    java.security.Key k = ks.getKey(a, null);
    if (k instanceof SecretKey) {
      keyCache.put(a, (SecretKey) k);
      return (SecretKey) k;
    }
    KeyGenerator gen = KeyGenerator.getInstance("AES", "AndroidKeyStore");
    gen.init(
        new KeyGenParameterSpec.Builder(
                a, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
    SecretKey generated = gen.generateKey();
    keyCache.put(a, generated);
    return generated;
  }

  /**
   * Software data key is unwrapped once per process; the durable key material stays
   * Keystore-wrapped.
   */
  private SecretKey key(int account, long owner) throws Exception {
    String name = alias(account, owner);
    SecretKey known = dataKeys.get(name);
    if (known != null) return known;
    SharedPreferences prefs =
        ApplicationLoader.applicationContext.getSharedPreferences(
            "tp_aic_data_keys", Context.MODE_PRIVATE);
    String field = "key_" + account + "_" + owner, stored = prefs.getString(field, null);
    byte[] address = ("AIC-data-key|" + account + "|" + owner).getBytes(StandardCharsets.UTF_8),
        plain;
    if (stored == null) {
      plain = new byte[32];
      new SecureRandom().nextBytes(plain);
      byte[] wrapped = CenterEnvelope.seal(wrappingKey(account, owner), address, plain);
      if (!prefs
          .edit()
          .putString(
              field, android.util.Base64.encodeToString(wrapped, android.util.Base64.NO_WRAP))
          .commit()) throw new IllegalStateException("Key persistence");
    } else
      plain =
          CenterEnvelope.open(
              wrappingKey(account, owner),
              address,
              android.util.Base64.decode(stored, android.util.Base64.NO_WRAP));
    try {
      SecretKey result = new javax.crypto.spec.SecretKeySpec(plain, "AES");
      dataKeys.put(name, result);
      return result;
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
  }

  private byte[] aad(int a, long o, String kind, String id) {
    return ("AIC1|" + a + "|" + o + "|" + kind + "|" + id).getBytes(StandardCharsets.UTF_8);
  }

  private static String digest(String s) throws Exception {
    byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    StringBuilder out = new StringBuilder();
    for (byte x : b) out.append(String.format(Locale.ROOT, "%02x", x & 255));
    return out.toString();
  }

  public synchronized void put(int a, long owner, String kind, String id, JSONObject json)
      throws Exception {
    if (!CenterNetwork.writeAllowed()) throw new InterruptedException();
    if (owner <= 0 || !CenterIndex.accountAllowed(a, owner))
      throw new SecurityException("Account unavailable");
    String h = digest(id);
    byte[] plain = json.toString().getBytes(StandardCharsets.UTF_8);
    byte[] sealed;
    try {
      sealed = CenterEnvelope.seal(key(a, owner), aad(a, owner, kind, h), plain);
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
    ContentValues v = new ContentValues();
    v.put("account", a);
    v.put("owner", owner);
    v.put("kind", kind);
    v.put("id", h);
    v.put("value", sealed);
    v.put("v", 2);
    v.put("stamp", System.currentTimeMillis());
    long previous = 0;
    try (Cursor old =
        getReadableDatabase()
            .rawQuery(
                "SELECT length(value) FROM records WHERE account=? AND owner=? AND kind=? AND id=?",
                new String[] {"" + a, "" + owner, kind, h})) {
      if (old.moveToFirst()) previous = old.getLong(0);
    }
    long next = bytes() - previous + sealed.length;
    if (next > CenterIndex.settings().optInt("indexMb", 256) * 1024L * 1024L
        && sealed.length > previous) throw new java.io.IOException(CenterText.get("storageLimit"));
    getWritableDatabase().insertWithOnConflict("records", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    cachedBytes = next;
  }

  /**
   * Android CursorWindow has a small per-row capacity; large encrypted documents use SQL slices.
   */
  private byte[] payload(
      int account, long owner, String kind, String hash, byte[] first, long length)
      throws Exception {
    if (length <= first.length) return first;
    if (length > Integer.MAX_VALUE) throw new java.io.IOException(CenterText.get("storageLimit"));
    byte[] result = new byte[(int) length];
    System.arraycopy(first, 0, result, 0, first.length);
    int offset = first.length;
    while (offset < result.length) {
      try (Cursor part =
          getReadableDatabase()
              .rawQuery(
                  "SELECT substr(value,?,524288) FROM records WHERE account=? AND owner=? AND"
                      + " kind=? AND id=?",
                  new String[] {"" + (offset + 1), "" + account, "" + owner, kind, hash})) {
        if (!part.moveToFirst()) throw new java.io.IOException("Missing encrypted record");
        byte[] bytes = part.getBlob(0);
        if (bytes.length == 0 || bytes.length > result.length - offset)
          throw new java.io.IOException("Invalid encrypted record");
        System.arraycopy(bytes, 0, result, offset, bytes.length);
        offset += bytes.length;
      }
    }
    return result;
  }

  public synchronized JSONObject get(int a, long owner, String kind, String id) throws Exception {
    if (!CenterIndex.accountAllowed(a, owner)) return null;
    String h = digest(id);
    try (Cursor c =
        getReadableDatabase()
            .query(
                "records",
                new String[] {"substr(value,1,524288)", "v", "length(value)"},
                "account=? AND owner=? AND kind=? AND id=?",
                new String[] {"" + a, "" + owner, kind, h},
                null,
                null,
                null)) {
      if (!c.moveToFirst()) return null;
      byte[] plain =
          CenterEnvelope.open(
              c.getInt(1) == 1 ? wrappingKey(a, owner) : key(a, owner),
              aad(a, owner, kind, h),
              payload(a, owner, kind, h, c.getBlob(0), c.getLong(2)));
      try {
        JSONObject value = new JSONObject(new String(plain, StandardCharsets.UTF_8));
        if (c.getInt(1) == 1) migrate(a, owner, kind, h, plain);
        return dependenciesAllowed(value) ? value : null;
      } finally {
        Arrays.fill(plain, (byte) 0);
      }
    }
  }

  public synchronized List<JSONObject> list(int a, long owner, String kind) throws Exception {
    return listInternal(a, owner, kind, true);
  }

  public interface Row {
    void accept(JSONObject value) throws Exception;
  }

  public synchronized void visit(int a, long owner, String kind, Row row) throws Exception {
    visitInternal(a, owner, kind, true, row);
  }

  private List<JSONObject> listInternal(int a, long owner, String kind, boolean checkDependencies)
      throws Exception {
    List<JSONObject> out = new ArrayList<>();
    visitInternal(a, owner, kind, checkDependencies, out::add);
    return out;
  }

  private void visitInternal(int a, long owner, String kind, boolean checkDependencies, Row row)
      throws Exception {
    if (!CenterIndex.accountAllowed(a, owner)) return;
    try (Cursor cursor =
        getReadableDatabase()
            .query(
                "records",
                new String[] {"id", "substr(value,1,524288)", "v", "length(value)"},
                "account=? AND owner=? AND kind=?",
                new String[] {"" + a, "" + owner, kind},
                null,
                null,
                null)) {
      while (cursor.moveToNext()) {
        String hash = cursor.getString(0);
        byte[] plain =
            CenterEnvelope.open(
                cursor.getInt(2) == 1 ? wrappingKey(a, owner) : key(a, owner),
                aad(a, owner, kind, hash),
                payload(a, owner, kind, hash, cursor.getBlob(1), cursor.getLong(3)));
        try {
          JSONObject value = new JSONObject(new String(plain, StandardCharsets.UTF_8));
          if (cursor.getInt(2) == 1) migrate(a, owner, kind, hash, plain);
          if (!checkDependencies || dependenciesAllowed(value)) row.accept(value);
        } finally {
          Arrays.fill(plain, (byte) 0);
        }
      }
    }
  }

  /** Scan encrypted payloads one at a time; never materialize an entire image cache. */
  public synchronized void removeWhere(
      int a, long owner, String kind, java.util.function.Predicate<JSONObject> predicate)
      throws Exception {
    if (!CenterIndex.accountAllowed(a, owner)) return;
    List<String> hashes = new ArrayList<>();
    try (Cursor cursor =
        getReadableDatabase()
            .query(
                "records",
                new String[] {"id", "substr(value,1,524288)", "v", "length(value)"},
                "account=? AND owner=? AND kind=?",
                new String[] {"" + a, "" + owner, kind},
                null,
                null,
                null)) {
      while (cursor.moveToNext()) {
        String hash = cursor.getString(0);
        byte[] plain =
            CenterEnvelope.open(
                cursor.getInt(2) == 1 ? wrappingKey(a, owner) : key(a, owner),
                aad(a, owner, kind, hash),
                payload(a, owner, kind, hash, cursor.getBlob(1), cursor.getLong(3)));
        try {
          if (predicate.test(new JSONObject(new String(plain, StandardCharsets.UTF_8))))
            hashes.add(hash);
        } finally {
          Arrays.fill(plain, (byte) 0);
        }
      }
    }
    for (String hash : hashes)
      getWritableDatabase()
          .delete(
              "records",
              "account=? AND owner=? AND kind=? AND id=?",
              new String[] {"" + a, "" + owner, kind, hash});
    cachedBytes = -1;
  }

  private static boolean dependenciesAllowed(JSONObject value) {
    JSONArray accounts = value.optJSONArray("accounts");
    for (int i = 0; accounts != null && i < accounts.length(); i++) {
      JSONObject account = accounts.optJSONObject(i);
      if (account == null
          || !CenterIndex.accountAllowed(account.optInt("account", -1), account.optLong("owner")))
        return false;
    }
    return true;
  }

  private void migrate(int a, long owner, String kind, String hash, byte[] plain) throws Exception {
    ContentValues values = new ContentValues();
    values.put("value", CenterEnvelope.seal(key(a, owner), aad(a, owner, kind, hash), plain));
    values.put("v", 2);
    getWritableDatabase()
        .update(
            "records",
            values,
            "account=? AND owner=? AND kind=? AND id=?",
            new String[] {"" + a, "" + owner, kind, hash});
    cachedBytes = -1;
  }

  public interface Work {
    void run() throws Exception;
  }

  public synchronized void transaction(Work work) throws Exception {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      work.run();
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
      cachedBytes = -1;
    }
  }

  public synchronized void putMessages(java.util.List<CenterData.Message> messages)
      throws Exception {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      for (CenterData.Message m : messages) {
        JSONObject old = get(m.account, m.owner, "message", m.qualified());
        if (old != null) {
          CenterData.Message prior = CenterData.Message.parse(old);
          if ((!m.mediaKey.isEmpty() && prior.mediaKey.equals(m.mediaKey))
              || prior.transport.equals(m.transport)) {
            m.contentHash = prior.contentHash;
            m.previewBase = prior.previewBase;
            m.ocr = prior.ocr;
            m.transcript = prior.transcript;
            m.transcriptKey = prior.transcriptKey;
            m.description = prior.text.equals(m.text) ? prior.description : "";
            m.hash = prior.hash;
            m.sceneHash = prior.sceneHash;
            m.pages = prior.pages;
            m.pageEvidence = prior.pageEvidence;
            m.frameEvidence = prior.frameEvidence;
            m.frameTimes.addAll(prior.frameTimes);
          }
        }
        put(m.account, m.owner, "message", m.qualified(), m.json());
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
      cachedBytes = -1;
    }
  }

  public synchronized void removeKind(int a, long owner, String kind) {
    cachedBytes = -1;
    getWritableDatabase()
        .delete(
            "records", "account=? AND owner=? AND kind=?", new String[] {"" + a, "" + owner, kind});
  }

  public synchronized void remove(int a, long owner, String kind, String id) throws Exception {
    if (kind.equals("report"))
      removeWhere(a, owner, "report_image", o -> id.equals(o.optString("report")));
    cachedBytes = -1;
    getWritableDatabase()
        .delete(
            "records",
            "account=? AND owner=? AND kind=? AND id=?",
            new String[] {"" + a, "" + owner, kind, digest(id)});
  }

  public synchronized void purge(int a) {
    cachedBytes = -1;
    for (int other = 0; other < UserConfig.MAX_ACCOUNT_COUNT; other++)
      if (other != a && UserConfig.getInstance(other).isClientActivated()) {
        long owner = UserConfig.getInstance(other).getClientUserId();
        for (String kind :
            new String[] {
              "report",
              "thread",
              "chunk",
              "final",
              "search",
              "vision_part",
              "report_image",
              "media_result",
              "job"
            })
          try {
            removeWhere(
                other,
                owner,
                kind,
                o -> {
                  JSONArray deps = o.optJSONArray("accounts");
                  for (int i = 0; deps != null && i < deps.length(); i++) {
                    JSONObject dep = deps.optJSONObject(i);
                    if (dep != null && dep.optInt("account") == a) return true;
                  }
                  return false;
                });
          } catch (Exception e) {
            FileLog.e(e);
          }
      }
    getWritableDatabase().delete("records", "account=?", new String[] {"" + a});
    try {
      KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
      ks.load(null);
      Enumeration<String> keys = ks.aliases();
      while (keys.hasMoreElements()) {
        String k = keys.nextElement();
        if (k.startsWith("tp_aic_" + a + "_")) {
          ks.deleteEntry(k);
          keyCache.remove(k);
          dataKeys.remove(k);
        }
      }
    } catch (Exception e) {
      FileLog.e(e);
    }
    SharedPreferences prefs =
        ApplicationLoader.applicationContext.getSharedPreferences(
            "tp_aic_data_keys", Context.MODE_PRIVATE);
    SharedPreferences.Editor editor = prefs.edit();
    for (String field : prefs.getAll().keySet())
      if (field.startsWith("key_" + a + "_")) editor.remove(field);
    editor.commit();
  }

  public synchronized void prune() {
    long now = System.currentTimeMillis(),
        days = Math.max(1, CenterIndex.settings().optInt("retentionDays", 90)) * 86400000L;
    getWritableDatabase()
        .delete(
            "records",
            "kind IN ('preview') AND stamp>0 AND stamp<?",
            new String[] {"" + (now - CenterIndex.settings().optInt("cacheHours", 24) * 3600000L)});
    getWritableDatabase()
        .delete(
            "records",
            "kind IN ('thread','chunk','final','search','vision_part','media_result') AND stamp>0"
                + " AND stamp<?",
            new String[] {"" + (now - days)});
    cachedBytes = -1;
  }

  public synchronized void clear() {
    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) purge(a);
  }

  public synchronized long bytes() {
    if (cachedBytes >= 0) return cachedBytes;
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT coalesce(sum(length(value)),0) FROM records", null)) {
      cachedBytes = c.moveToFirst() ? c.getLong(0) : 0;
      return cachedBytes;
    }
  }
}
