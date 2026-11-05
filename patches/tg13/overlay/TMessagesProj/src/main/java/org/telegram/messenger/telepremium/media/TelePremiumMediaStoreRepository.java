package org.telegram.messenger.telepremium.media;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Device media provider backed by Android MediaStore instead of broad filesystem traversal. */
public final class TelePremiumMediaStoreRepository {
    public interface Callback { void onLoaded(List<TelePremiumMediaItem> items, String error); }
    public interface FoldersCallback { void onLoaded(LinkedHashMap<String, Integer> audio, LinkedHashMap<String, Integer> video); }

    private static final String PREFS = "telepremium_media_folders";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Selected folders for music ({@code video=false}) or video; empty = every folder. */
    public static Set<String> getFolders(boolean video) {
        return new HashSet<>(prefs().getStringSet(video ? "video" : "audio", new HashSet<>()));
    }

    public static void setFolders(boolean video, Set<String> folders) {
        prefs().edit().putStringSet(video ? "video" : "audio", new HashSet<>(folders)).apply();
    }

    private static boolean allowed(Set<String> selected, String folder) {
        if (selected.isEmpty()) return true;
        if (folder == null) return false;
        for (String f : selected) if (folder.equalsIgnoreCase(f) || folder.toLowerCase().startsWith(f.toLowerCase() + "/")) return true;
        return false;
    }

    /** All folders that contain music / video, with file counts, sorted by name. */
    public static void loadFolders(FoldersCallback callback) {
        new Thread(() -> {
            LinkedHashMap<String, Integer> audio = new LinkedHashMap<>(), video = new LinkedHashMap<>();
            try {
                ArrayList<TelePremiumMediaItem> items = new ArrayList<>();
                TelePremiumMediaStoreRepository repo = new TelePremiumMediaStoreRepository();
                repo.loadAudio(items, null);
                count(items, audio);
                items.clear();
                repo.loadVideo(items, null);
                count(items, video);
            } catch (Throwable ignore) {
            }
            AndroidUtilities.runOnUIThread(() -> callback.onLoaded(audio, video));
        }, "TelePremiumFolders").start();
    }

    private static void count(List<TelePremiumMediaItem> items, LinkedHashMap<String, Integer> out) {
        TreeMap<String, Integer> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (TelePremiumMediaItem item : items) {
            String f = item.folder == null ? "?" : item.folder;
            Integer n = sorted.get(f);
            sorted.put(f, n == null ? 1 : n + 1);
        }
        out.putAll(sorted);
    }

    /** "Music/Albums" from RELATIVE_PATH (Android 10+) or the file path. */
    private static String folderOf(Cursor cursor, int relative, int data) {
        String value = null;
        if (relative >= 0) value = cursor.getString(relative);
        if (value == null && data >= 0) {
            String path = cursor.getString(data);
            if (path != null) {
                int slash = path.lastIndexOf('/');
                value = slash > 0 ? path.substring(0, slash) : path;
                // strip /storage/emulated/0 or /storage/XXXX-XXXX
                String[] parts = value.split("/");
                int skip = value.startsWith("/storage/emulated/") ? 4 : value.startsWith("/storage/") ? 3 : value.startsWith("/sdcard") ? 2 : 0;
                StringBuilder b = new StringBuilder();
                for (int i = skip; i < parts.length; i++) {
                    if (parts[i].isEmpty()) continue;
                    if (b.length() > 0) b.append('/');
                    b.append(parts[i]);
                }
                value = b.toString();
            }
        }
        if (value == null) return null;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    public void loadAll(Callback callback) {
        new Thread(() -> {
            ArrayList<TelePremiumMediaItem> out = new ArrayList<>();
            String error = null;
            try {
                loadAudio(out, getFolders(false));
                loadVideo(out, getFolders(true));
                Collections.sort(out, (a, b) -> Long.compare(b.duration, a.duration));
            } catch (Throwable t) {
                error = t.getMessage();
            }
            ArrayList<TelePremiumMediaItem> result = out;
            String failure = error;
            AndroidUtilities.runOnUIThread(() -> callback.onLoaded(result, failure));
        }, "TelePremiumMediaStore").start();
    }

    private void loadAudio(List<TelePremiumMediaItem> out, Set<String> folders) {
        ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                Build.VERSION.SDK_INT >= 29 ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DATA
        };
        try (Cursor cursor = resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                MediaStore.Audio.Media.IS_MUSIC + "!=0", null, MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (cursor == null) return;
            int id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int name = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
            int mime = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE);
            int size = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
            int duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int relative = Build.VERSION.SDK_INT >= 29 ? cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) : -1;
            int data = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
            while (cursor.moveToNext()) {
                String folder = folderOf(cursor, relative, data);
                if (folders != null && !allowed(folders, folder)) continue;
                Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(id));
                TelePremiumMediaItem item = TelePremiumMediaItem.device(uri.toString(), cursor.getString(name), cursor.getString(mime), cursor.getLong(duration), cursor.getLong(size));
                item.artist = cursor.getString(artist);
                item.album = cursor.getString(album);
                item.mediaType = TelePremiumMediaItem.TYPE_AUDIO;
                item.folder = folder;
                out.add(item);
            }
        }
    }

    private void loadVideo(List<TelePremiumMediaItem> out, Set<String> folders) {
        ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION,
                Build.VERSION.SDK_INT >= 29 ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DATA
        };
        try (Cursor cursor = resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection,
                null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cursor == null) return;
            int id = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int name = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int mime = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE);
            int size = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int duration = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
            int relative = Build.VERSION.SDK_INT >= 29 ? cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) : -1;
            int data = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
            while (cursor.moveToNext()) {
                String folder = folderOf(cursor, relative, data);
                if (folders != null && !allowed(folders, folder)) continue;
                Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(id));
                TelePremiumMediaItem item = TelePremiumMediaItem.device(uri.toString(), cursor.getString(name), cursor.getString(mime), cursor.getLong(duration), cursor.getLong(size));
                item.mediaType = TelePremiumMediaItem.TYPE_VIDEO;
                item.folder = folder;
                out.add(item);
            }
        }
    }
}
