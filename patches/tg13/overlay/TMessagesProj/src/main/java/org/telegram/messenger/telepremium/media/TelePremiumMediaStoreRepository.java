package org.telegram.messenger.telepremium.media;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Device media provider backed by Android MediaStore instead of broad filesystem traversal. */
public final class TelePremiumMediaStoreRepository {
    public interface Callback { void onLoaded(List<TelePremiumMediaItem> items, String error); }

    public void loadAll(Callback callback) {
        new Thread(() -> {
            ArrayList<TelePremiumMediaItem> out = new ArrayList<>();
            String error = null;
            try {
                loadAudio(out);
                loadVideo(out);
                Collections.sort(out, (a, b) -> Long.compare(b.duration, a.duration));
            } catch (Throwable t) {
                error = t.getMessage();
            }
            ArrayList<TelePremiumMediaItem> result = out;
            String failure = error;
            AndroidUtilities.runOnUIThread(() -> callback.onLoaded(result, failure));
        }, "TelePremiumMediaStore").start();
    }

    private void loadAudio(List<TelePremiumMediaItem> out) {
        ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM
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
            while (cursor.moveToNext()) {
                Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(id));
                TelePremiumMediaItem item = TelePremiumMediaItem.device(uri.toString(), cursor.getString(name), cursor.getString(mime), cursor.getLong(duration), cursor.getLong(size));
                item.artist = cursor.getString(artist);
                item.album = cursor.getString(album);
                item.mediaType = TelePremiumMediaItem.TYPE_AUDIO;
                out.add(item);
            }
        }
    }

    private void loadVideo(List<TelePremiumMediaItem> out) {
        ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION
        };
        try (Cursor cursor = resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection,
                null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cursor == null) return;
            int id = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int name = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int mime = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE);
            int size = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int duration = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
            while (cursor.moveToNext()) {
                Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(id));
                TelePremiumMediaItem item = TelePremiumMediaItem.device(uri.toString(), cursor.getString(name), cursor.getString(mime), cursor.getLong(duration), cursor.getLong(size));
                item.mediaType = TelePremiumMediaItem.TYPE_VIDEO;
                out.add(item);
            }
        }
    }
}
