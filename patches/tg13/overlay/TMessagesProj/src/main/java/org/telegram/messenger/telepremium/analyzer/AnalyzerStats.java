package org.telegram.messenger.telepremium.analyzer;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local statistics over already loaded messages: no AI, no network. */
public final class AnalyzerStats {
    public static final class Author {
        public final long id;
        public final String name;
        public final int count;
        public final int media;

        Author(long id, String name, int count, int media) {
            this.id = id;
            this.name = name;
            this.count = count;
            this.media = media;
        }
    }

    public static final class Term {
        public final String text;
        public final int count;

        Term(String text, int count) {
            this.text = text;
            this.count = count;
        }
    }

    public int total;
    public int photos, videos, documents, links;
    public int[] perDay = new int[0];
    public long firstDate, lastDate;
    public final List<Author> authors = new ArrayList<>();
    public final List<Term> instruments = new ArrayList<>();
    public final List<Term> tags = new ArrayList<>();

    private static final Pattern LINK = Pattern.compile("https?://|t\\.me/", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("[#$]([\\p{L}][\\p{L}\\p{N}_]{1,30})");
    private static final Set<String> CODES = new HashSet<>(java.util.Arrays.asList(
            "USD", "EUR", "GBP", "JPY", "CHF", "AUD", "NZD", "CAD", "RUB", "CNY", "TRY", "XAU", "XAG", "BTC", "ETH", "NZD", "SEK", "NOK", "PLN", "MXN", "ZAR", "SGD", "HKD"));
    private static final Pattern PAIR = Pattern.compile("\\b([A-Za-z]{3})\\s?/?\\s?([A-Za-z]{3})\\b");
    private static final Set<String> INDEX = new HashSet<>(java.util.Arrays.asList("NASDAQ", "SPX", "DAX", "DJI", "BRENT", "WTI", "GOLD", "SILVER", "IMOEX", "RTS"));

    public static AnalyzerStats compute(List<AnalyzerHistoryReader.Entry> entries, int days, int account) {
        AnalyzerStats s = new AnalyzerStats();
        if (entries == null || entries.isEmpty()) return s;
        s.total = entries.size();
        int buckets = Math.max(1, Math.min(days, 90));
        s.perDay = new int[buckets];
        long now = System.currentTimeMillis() / 1000L;
        HashMap<Long, int[]> byAuthor = new HashMap<>();
        HashMap<String, Integer> instr = new HashMap<>();
        HashMap<String, Integer> tagMap = new HashMap<>();
        s.firstDate = Integer.MAX_VALUE;
        for (AnalyzerHistoryReader.Entry e : entries) {
            s.firstDate = Math.min(s.firstDate, e.date);
            s.lastDate = Math.max(s.lastDate, e.date);
            int day = (int) ((now - e.date) / 86400L);
            if (day >= 0 && day < buckets) s.perDay[buckets - 1 - day]++;
            int[] a = byAuthor.get(e.senderId);
            if (a == null) byAuthor.put(e.senderId, a = new int[2]);
            a[0]++;
            switch (e.mediaType) {
                case "photo": case "image": s.photos++; a[1]++; break;
                case "video": s.videos++; a[1]++; break;
                case "pdf": case "document": case "audio": s.documents++; a[1]++; break;
                default: break;
            }
            String text = e.caption == null ? "" : e.caption;
            if (LINK.matcher(text).find()) s.links++;
            Matcher t = TAG.matcher(text);
            while (t.find()) {
                String key = t.group(1).toUpperCase(Locale.ROOT);
                tagMap.merge(key, 1, Integer::sum);
            }
            Matcher p = PAIR.matcher(text);
            while (p.find()) {
                String x = p.group(1).toUpperCase(Locale.ROOT), y = p.group(2).toUpperCase(Locale.ROOT);
                if (CODES.contains(x) && CODES.contains(y) && !x.equals(y)) instr.merge(x + "/" + y, 1, Integer::sum);
            }
            String upper = text.toUpperCase(Locale.ROOT);
            for (String name : INDEX) if (upper.contains(name)) instr.merge(name, 1, Integer::sum);
            if (upper.contains("XAUUSD") || upper.contains("ЗОЛОТ")) instr.merge("XAU/USD", 1, Integer::sum);
        }
        for (Map.Entry<Long, int[]> a : byAuthor.entrySet()) {
            s.authors.add(new Author(a.getKey(), name(account, a.getKey()), a.getValue()[0], a.getValue()[1]));
        }
        Collections.sort(s.authors, (l, r) -> Integer.compare(r.count, l.count));
        s.instruments.addAll(top(instr, 8));
        s.tags.addAll(top(tagMap, 8));
        return s;
    }

    private static List<Term> top(Map<String, Integer> map, int n) {
        ArrayList<Term> list = new ArrayList<>();
        for (Map.Entry<String, Integer> e : map.entrySet()) list.add(new Term(e.getKey(), e.getValue()));
        Collections.sort(list, (l, r) -> Integer.compare(r.count, l.count));
        return list.size() > n ? new ArrayList<>(list.subList(0, n)) : list;
    }

    public static String name(int account, long id) {
        if (id == 0) return "—";
        try {
            if (id > 0) {
                TLRPC.User u = MessagesController.getInstance(account).getUser(id);
                if (u != null) return UserObject.getUserName(u);
            } else {
                TLRPC.Chat c = MessagesController.getInstance(account).getChat(-id);
                if (c != null && c.title != null) return c.title;
            }
        } catch (Throwable ignored) {
        }
        return "id " + id;
    }

    /** Compact facts block for the AI prompt, so "who posts what / how much" is exact, not guessed. */
    public String toPromptText(int maxAuthors) {
        StringBuilder out = new StringBuilder();
        out.append("Статистика выборки: сообщений ").append(total).append(", фото ").append(photos)
                .append(", видео ").append(videos).append(", документов ").append(documents)
                .append(", ссылок ").append(links).append(".\n");
        int n = Math.min(maxAuthors, authors.size());
        if (n > 0) {
            out.append("Активные авторы: ");
            for (int i = 0; i < n; i++) {
                Author a = authors.get(i);
                if (i > 0) out.append("; ");
                out.append(a.name).append(" (id ").append(a.id).append(") — ").append(a.count)
                        .append(" сообщ., медиа ").append(a.media);
            }
            out.append("\n");
        }
        if (!instruments.isEmpty()) {
            out.append("Упоминаемые инструменты: ");
            for (int i = 0; i < instruments.size(); i++) {
                if (i > 0) out.append(", ");
                out.append(instruments.get(i).text).append(" ×").append(instruments.get(i).count);
            }
            out.append("\n");
        }
        if (!tags.isEmpty()) {
            out.append("Частые теги: ");
            for (int i = 0; i < tags.size(); i++) {
                if (i > 0) out.append(", ");
                out.append('#').append(tags.get(i).text).append(" ×").append(tags.get(i).count);
            }
            out.append("\n");
        }
        return out.toString();
    }
}
