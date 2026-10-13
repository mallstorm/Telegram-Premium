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

    public synchronized void replace(List<TelePremiumMediaItem> source, int startIndex) {
        items.clear();
        if (source != null) items.addAll(source);
        currentIndex = items.isEmpty() ? -1 : Math.max(0, Math.min(items.size() - 1, startIndex));
        notifyChanged();
    }

    public synchronized void append(TelePremiumMediaItem item) {
        if (item == null) return;
        items.add(item);
        if (currentIndex < 0) currentIndex = 0;
        notifyChanged();
    }

    public synchronized void appendAll(List<TelePremiumMediaItem> source) {
        if (source == null || source.isEmpty()) return;
        items.addAll(source);
        if (currentIndex < 0) currentIndex = 0;
        notifyChanged();
    }

    public synchronized void insertNext(TelePremiumMediaItem item) {
        if (item == null) return;
        int at = currentIndex < 0 ? items.size() : Math.min(items.size(), currentIndex + 1);
        items.add(at, item);
        if (currentIndex < 0) currentIndex = 0;
        notifyChanged();
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
