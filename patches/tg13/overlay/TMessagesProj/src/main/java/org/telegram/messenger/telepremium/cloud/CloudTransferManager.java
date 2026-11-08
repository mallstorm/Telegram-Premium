package org.telegram.messenger.telepremium.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.UUID;

public final class CloudTransferManager {
    public static final int TYPE_UPLOAD = 1;
    public static final int TYPE_DOWNLOAD = 2;

    public static final int QUEUED = 0;
    public static final int PREPARING = 1;
    public static final int UPLOADING = 2;
    public static final int DOWNLOADING = 3;
    public static final int WAITING_NETWORK = 4;
    public static final int RETRYING = 5;
    public static final int FAILED_RETRYABLE = 6;
    public static final int COMPLETED = 7;
    public static final int CANCELLED = 8;
    /** Telegram has the .tpv payload; encrypted index is not confirmed yet. */
    public static final int INDEXING = 9;

    private static final CloudTransferManager[] INSTANCES = new CloudTransferManager[UserConfig.MAX_ACCOUNT_COUNT];

    public interface Listener {
        void onTransferChanged(Task task);
    }

    public static final class Task {
        public String id;
        public int type;
        public int state;
        public String nodeId;
        public String fileName;
        public String uri;
        public String mime;
        public String parentId;
        public long messageId;
        public long transferred;
        public long total;
        public long updatedAt;
        public int retryCount;
        public float speedBytesPerSec;
        public String error;

        public Task copy() {
            Task t = new Task();
            t.id=id; t.type=type; t.state=state; t.nodeId=nodeId; t.fileName=fileName; t.uri=uri;
            t.mime=mime; t.parentId=parentId; t.messageId=messageId; t.transferred=transferred;
            t.total=total; t.updatedAt=updatedAt; t.retryCount=retryCount;
            t.speedBytesPerSec=speedBytesPerSec; t.error=error;
            return t;
        }

        public int percent() {
            if (total <= 0) return 0;
            return (int)Math.max(0, Math.min(100, Math.round(transferred * 100f / total)));
        }
    }

    public static synchronized CloudTransferManager getInstance(int account) {
        if (INSTANCES[account] == null || INSTANCES[account].epoch != TelePremiumCloudResetState.epoch()) INSTANCES[account] = new CloudTransferManager(account);
        return INSTANCES[account];
    }

    private final long epoch = TelePremiumCloudResetState.epoch();
    private final int account;
    private final SharedPreferences prefs;
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private Task task;
    private long lastSampleBytes;
    private long lastSampleTime;

    private CloudTransferManager(int account) {
        this.account = account;
        prefs = ApplicationLoader.applicationContext.getSharedPreferences("tp_cloud_transfer_" + account, Context.MODE_PRIVATE);
        task = load();
        if (task != null && (task.state == PREPARING || task.state == UPLOADING || task.state == DOWNLOADING || task.state == RETRYING || task.state == QUEUED)) {
            task.state = WAITING_NETWORK;
            task.error = "Операция будет продолжена";
            persist();
        }
    }

    public synchronized Task getTask() {
        return task == null ? null : task.copy();
    }

    public synchronized void addListener(Listener listener) {
        if (listener == null || listeners.contains(listener)) return;
        listeners.add(listener);
        Task snapshot = task == null ? null : task.copy();
        AndroidUtilities.runOnUIThread(() -> listener.onTransferChanged(snapshot));
    }

    public synchronized void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public synchronized Task beginUpload(String uri, String parentId, String fileName, String mime, long total) {
        Task t = new Task();
        t.id = UUID.randomUUID().toString();
        t.type = TYPE_UPLOAD;
        t.state = PREPARING;
        t.uri = uri;
        t.parentId = parentId;
        t.fileName = fileName;
        t.mime = mime;
        t.total = Math.max(0, total);
        t.updatedAt = System.currentTimeMillis();
        task = t;
        resetSpeed();
        persist();
        notifyListeners();
        return t.copy();
    }

    public synchronized Task beginDownload(String nodeId, String fileName, long total, long messageId) {
        Task t = new Task();
        t.id = UUID.randomUUID().toString();
        t.type = TYPE_DOWNLOAD;
        t.state = DOWNLOADING;
        t.nodeId = nodeId;
        t.fileName = fileName;
        t.total = Math.max(0, total);
        t.messageId = messageId;
        t.updatedAt = System.currentTimeMillis();
        task = t;
        resetSpeed();
        persist();
        notifyListeners();
        return t.copy();
    }

    public synchronized void markUploading() {
        if (task == null) return;
        task.state = UPLOADING;
        task.error = null;
        task.updatedAt = System.currentTimeMillis();
        persist();
        notifyListeners();
    }

    public synchronized void updateProgress(long transferred, long total) {
        if (task == null) return;
        long now = SystemClock.elapsedRealtime();
        if (lastSampleTime > 0 && now > lastSampleTime && transferred >= lastSampleBytes) {
            task.speedBytesPerSec = (transferred - lastSampleBytes) * 1000f / (now - lastSampleTime);
        }
        lastSampleTime = now;
        lastSampleBytes = transferred;
        task.transferred = Math.max(0, transferred);
        if (total > 0) task.total = total;
        task.state = task.type == TYPE_UPLOAD ? UPLOADING : DOWNLOADING;
        task.error = null;
        task.updatedAt = System.currentTimeMillis();
        persist();
        notifyListeners();
    }

    public synchronized void waitingNetwork(String reason) {
        if (task == null) return;
        task.state = WAITING_NETWORK;
        task.error = reason == null ? "Ожидание сети" : reason;
        task.speedBytesPerSec = 0;
        task.updatedAt = System.currentTimeMillis();
        persist();
        notifyListeners();
    }

    public synchronized void retrying() {
        if (task == null) return;
        task.state = RETRYING;
        task.retryCount++;
        task.error = null;
        task.updatedAt = System.currentTimeMillis();
        persist();
        notifyListeners();
    }

    public synchronized void failRetryable(String error) {
        if (task == null) return;
        task.state = FAILED_RETRYABLE;
        task.error = error;
        task.speedBytesPerSec = 0;
        task.updatedAt = System.currentTimeMillis();
        persist();
        notifyListeners();
    }

    public synchronized void markIndexing(String nodeId,long messageId) {
        if(task==null || task.type!=TYPE_UPLOAD)return;
        task.nodeId=nodeId;
        task.messageId=messageId;
        task.state=INDEXING;
        task.error=null;
        task.updatedAt=System.currentTimeMillis();
        persist();notifyListeners();
    }

    public synchronized void complete() {
        if (task == null) return;
        task.state = COMPLETED;
        if (task.total > 0) task.transferred = task.total;
        task.speedBytesPerSec = 0;
        task.error = null;
        task.updatedAt = System.currentTimeMillis();
        persist();
        notifyListeners();
    }

    public synchronized void cancel() {
        if (task == null) return;
        task.state = CANCELLED;
        task.speedBytesPerSec = 0;
        task.updatedAt = System.currentTimeMillis();
        persist();
        notifyListeners();
    }

    public synchronized void clear() {
        task = null;
        prefs.edit().clear().apply();
        resetSpeed();
        notifyListeners();
    }

    public static String stateLabel(Task task) {
        if (task == null) return "";
        switch (task.state) {
            case PREPARING: return "Подготовка файла";
            case UPLOADING: return "Загрузка в облако";
            case DOWNLOADING: return "Скачивание из облака";
            case WAITING_NETWORK: return "Ожидание сети";
            case RETRYING: return "Возобновление передачи";
            case FAILED_RETRYABLE: return "Можно продолжить";
            case INDEXING: return "Сохранение зашифрованного каталога";
            case COMPLETED: return "Готово";
            case CANCELLED: return "Отменено";
            default: return "В очереди";
        }
    }

    private void resetSpeed() {
        lastSampleBytes = 0;
        lastSampleTime = 0;
    }

    private void notifyListeners() {
        if (!TelePremiumCloudResetState.allowed(epoch)) return;
        Task snapshot = task == null ? null : task.copy();
        ArrayList<Listener> copy = new ArrayList<>(listeners);
        AndroidUtilities.runOnUIThread(() -> {
            if (!TelePremiumCloudResetState.allowed(epoch)) return;
            for (Listener listener : copy) listener.onTransferChanged(snapshot == null ? null : snapshot.copy());
        });
    }

    private void persist() {
        if (!TelePremiumCloudResetState.allowed(epoch)) return;
        if (task == null) {
            prefs.edit().clear().apply();
            return;
        }
        prefs.edit()
                .putString("id", task.id)
                .putInt("type", task.type)
                .putInt("state", task.state)
                .putString("nodeId", task.nodeId)
                .putString("fileName", task.fileName)
                .putString("uri", task.uri)
                .putString("mime", task.mime)
                .putString("parentId", task.parentId)
                .putLong("messageId", task.messageId)
                .putLong("transferred", task.transferred)
                .putLong("total", task.total)
                .putLong("updatedAt", task.updatedAt)
                .putInt("retryCount", task.retryCount)
                .putFloat("speed", task.speedBytesPerSec)
                .putString("error", task.error)
                .apply();
    }

    private Task load() {
        String id = prefs.getString("id", null);
        if (id == null) return null;
        Task t = new Task();
        t.id = id;
        t.type = prefs.getInt("type", TYPE_UPLOAD);
        t.state = prefs.getInt("state", QUEUED);
        t.nodeId = prefs.getString("nodeId", null);
        t.fileName = prefs.getString("fileName", null);
        t.uri = prefs.getString("uri", null);
        t.mime = prefs.getString("mime", null);
        t.parentId = prefs.getString("parentId", "root");
        t.messageId = prefs.getLong("messageId", 0);
        t.transferred = prefs.getLong("transferred", 0);
        t.total = prefs.getLong("total", 0);
        t.updatedAt = prefs.getLong("updatedAt", 0);
        t.retryCount = prefs.getInt("retryCount", 0);
        t.speedBytesPerSec = prefs.getFloat("speed", 0);
        t.error = prefs.getString("error", null);
        return t;
    }
}
