package org.telegram.messenger.telepremium.media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-process queue shared by video, audio, Cloud, device and network sources. */
public final class TelePremiumQueueManager {
    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ONE = 1;
    public static final int REPEAT_ALL = 2;

    public interface Listener { void onQueueChanged(List<TelePremiumMediaItem> items, int currentIndex); }

    private static final TelePremiumQueueManager INSTANCE = new TelePremiumQueueManager();
    public static TelePremiumQueueManager getInstance() { return INSTANCE; }

    private final ArrayList<TelePremiumMediaItem> items = new ArrayList<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private int currentIndex = -1;
    private int repeatMode = REPEAT_OFF;
    private boolean shuffle;

    private TelePremiumQueueManager() {}

    public synchronized List<TelePremiumMediaItem> snapshot() { return new ArrayList<>(items); }
    public synchronized int getCurrentIndex() { return currentIndex; }
    public synchronized int size() { return items.size(); }
    public synchronized boolean isShuffle() { return shuffle; }
    public synchronized int getRepeatMode() { return repeatMode; }

    public synchronized TelePremiumMediaItem current() {
        return currentIndex >= 0 && currentIndex < items.size() ? items.get(currentIndex) : null;
    }

    /**
     * The queue holds one kind of media only: music (including radio/audio streams) or video.
     * Shuffle and repeat therefore always stay inside that kind.
     */
    public static boolean sameKind(TelePremiumMediaItem a, TelePremiumMediaItem b) {
        return a != null && b != null && a.isVideo() == b.isVideo();
    }

    public synchronized boolean accepts(TelePremiumMediaItem item) {
        return item != null && (items.isEmpty() || sameKind(items.get(0), item));
    }

    /** True when the queue currently holds video. */
    public synchronized boolean isVideoQueue() {
        return !items.isEmpty() && items.get(0).isVideo();
    }

    /** Replaces the queue; items of the other kind than the start item are dropped. */
    public synchronized void replace(List<TelePremiumMediaItem> source, int startIndex) {
        items.clear();
        currentIndex = -1;
        if (source != null && !source.isEmpty()) {
            int start = Math.max(0, Math.min(source.size() - 1, startIndex));
            TelePremiumMediaItem anchor = source.get(start);
            for (int i = 0; i < source.size(); i++) {
                TelePremiumMediaItem item = source.get(i);
                if (!sameKind(anchor, item)) continue;
                if (i == start) currentIndex = items.size();
                items.add(item);
            }
        }
        notifyChanged();
    }

    /** @return false when the item is of the other kind than the queue */
    public synchronized boolean append(TelePremiumMediaItem item) {
        if (!accepts(item)) return false;
        items.add(item);
        if (currentIndex < 0) currentIndex = 0;
        notifyChanged();
        return true;
    }

    public synchronized void appendAll(List<TelePremiumMediaItem> source) {
        if (source == null || source.isEmpty()) return;
        boolean changed = false;
        for (TelePremiumMediaItem item : source) {
            if (!accepts(item)) continue;
            items.add(item);
            changed = true;
        }
        if (!changed) return;
        if (currentIndex < 0) currentIndex = 0;
        notifyChanged();
    }

    /** @return false when the item is of the other kind than the queue */
    public synchronized boolean insertNext(TelePremiumMediaItem item) {
        if (!accepts(item)) return false;
        int at = currentIndex < 0 ? items.size() : Math.min(items.size(), currentIndex + 1);
        items.add(at, item);
        if (currentIndex < 0) currentIndex = 0;
        notifyChanged();
        return true;
    }

    /** The item that plays after the current one (without shuffle), or null. */
    public synchronized TelePremiumMediaItem peekNext() {
        if (items.isEmpty()) return null;
        if (currentIndex + 1 < items.size()) return items.get(currentIndex + 1);
        return repeatMode == REPEAT_ALL ? items.get(0) : null;
    }

    public synchronized void remove(int index) {
        if (index < 0 || index >= items.size()) return;
        items.remove(index);
        if (items.isEmpty()) currentIndex = -1;
        else if (index < currentIndex) currentIndex--;
        else if (currentIndex >= items.size()) currentIndex = items.size() - 1;
        notifyChanged();
    }

    public synchronized void move(int from, int to) {
        if (from < 0 || from >= items.size() || to < 0 || to >= items.size() || from == to) return;
        TelePremiumMediaItem item = items.remove(from);
        items.add(to, item);
        if (currentIndex == from) currentIndex = to;
        else if (from < currentIndex && to >= currentIndex) currentIndex--;
        else if (from > currentIndex && to <= currentIndex) currentIndex++;
        notifyChanged();
    }

    public synchronized void clear() {
        items.clear();
        currentIndex = -1;
        notifyChanged();
    }

    public synchronized void setCurrentIndex(int index) {
        if (index < 0 || index >= items.size()) return;
        currentIndex = index;
        notifyChanged();
    }

    public synchronized TelePremiumMediaItem next() {
        if (items.isEmpty()) return null;
        if (repeatMode == REPEAT_ONE && currentIndex >= 0) return items.get(currentIndex);
        if (shuffle && items.size() > 1) {
            int next = currentIndex;
            while (next == currentIndex) next = (int) (Math.random() * items.size());
            currentIndex = next;
            notifyChanged();
            return items.get(currentIndex);
        }
        if (currentIndex + 1 < items.size()) currentIndex++;
        else if (repeatMode == REPEAT_ALL) currentIndex = 0;
        else return null;
        notifyChanged();
        return items.get(currentIndex);
    }

    public synchronized TelePremiumMediaItem previous() {
        if (items.isEmpty()) return null;
        if (currentIndex > 0) currentIndex--;
        else if (repeatMode == REPEAT_ALL) currentIndex = items.size() - 1;
        else currentIndex = 0;
        notifyChanged();
        return items.get(currentIndex);
    }

    public synchronized void setShuffle(boolean value) { shuffle = value; notifyChanged(); }
    public synchronized void setRepeatMode(int mode) {
        repeatMode = mode < REPEAT_OFF || mode > REPEAT_ALL ? REPEAT_OFF : mode;
        notifyChanged();
    }

    public synchronized void shuffleQueue() {
        TelePremiumMediaItem current = current();
        Collections.shuffle(items);
        currentIndex = current == null ? (items.isEmpty() ? -1 : 0) : items.indexOf(current);
        notifyChanged();
    }

    public void addListener(Listener listener) { if (listener != null) listeners.addIfAbsent(listener); }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    private void notifyChanged() {
        List<TelePremiumMediaItem> copy = new ArrayList<>(items);
        int index = currentIndex;
        for (Listener listener : listeners) listener.onQueueChanged(copy, index);
    }
}
