package org.telegram.messenger.telepremium.analyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Local, deterministic retrieval prior to a metered LLM request.
 * No remote embedding/indexing service and no cross-account document mixing.
 */
public final class AnalyzerRetrieval {
    private AnalyzerRetrieval() { }

    private static final class Candidate {
        final AnalyzerHistoryReader.Entry entry;
        final int score;
        Candidate(AnalyzerHistoryReader.Entry entry, int score) {
            this.entry = entry;
            this.score = score;
        }
    }

    public static List<AnalyzerHistoryReader.Entry> select(
            List<AnalyzerHistoryReader.Entry> input, String question, long authorFilter, int max) {
        if (input == null || input.isEmpty() || max <= 0) return Collections.emptyList();
        int limit = Math.max(1, Math.min(150, max));
        String lower = question == null ? "" : question.toLowerCase(Locale.ROOT);
        boolean general = lower.contains("сводк") || lower.contains("обзор") ||
                lower.contains("о чём") || lower.contains("о чем") ||
                lower.contains("расскажи про групп") || lower.contains("summary");
        Set<String> words = new HashSet<>();
        for (String word : lower.split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3 && word.length() < 50 &&
                    !word.matches("\\d{1,2}") &&
                    !word.equals("группы") && !word.equals("группе") &&
                    !word.equals("сообщения") && !word.equals("публикации") &&
                    !word.equals("последние") && !word.equals("которые") &&
                    !word.equals("пользователя") && !word.equals("покажи")) {
                words.add(word);
            }
        }

        ArrayList<AnalyzerHistoryReader.Entry> pool = new ArrayList<>();
        for (AnalyzerHistoryReader.Entry entry : input) {
            if (entry == null || (authorFilter != 0L && authorFilter != entry.senderId)) continue;
            pool.add(entry);
        }
        if (pool.isEmpty()) return Collections.emptyList();

        ArrayList<Candidate> ranked = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            AnalyzerHistoryReader.Entry entry = pool.get(i);
            String caption = entry.caption == null ? "" : entry.caption.toLowerCase(Locale.ROOT);
            int score = i / Math.max(1, pool.size() / 18);
            for (String word : words) {
                if (caption.contains(word)) score += 12;
                if (entry.mediaType.contains(word)) score += 12;
                if (String.valueOf(entry.senderId).equals(word)) score += 30;
            }
            if (general && i % Math.max(1, pool.size() / 18) == 0) score += 7;
            if ("text".equals(entry.mediaType) && !caption.isEmpty()) score += 2;
            if (general && !caption.isEmpty()) score += 2;
            ranked.add(new Candidate(entry, score));
        }
        ranked.sort((left, right) -> {
            int compare = Integer.compare(right.score, left.score);
            if (compare != 0) return compare;
            return Integer.compare(right.entry.date, left.entry.date);
        });
        ArrayList<AnalyzerHistoryReader.Entry> selection = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, ranked.size()); i++) selection.add(ranked.get(i).entry);
        selection.sort(Comparator.comparingInt(a -> a.date));
        return selection;
    }
}
