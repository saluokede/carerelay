package com.carerelay.station;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 新版工作台 · 本地康复知识库检索（RAG 的“知识从哪来”部分）。
 *
 * <p>知识条目存在 {@code kb_entries} 表，检索用 BM25：中文按「相邻两字」切词，
 * 英文与数字按整词切；命中结果带文档名与条目号，回答里可以直接引用
 * 「《股骨置换术后居家康复规范》第 3 条」，医生能回查原文，而不是听模型凭记忆编。</p>
 */
@Service
public class StationRagService {

    private static final Pattern SEGMENT = Pattern.compile("[\\u4e00-\\u9fa5]+|[a-z0-9]+");
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final long TTL_MS = 60_000L;

    private final JdbcTemplate jdbc;
    private final AtomicLong loadedAt = new AtomicLong(0);
    private volatile List<Entry> index = List.of();
    private volatile double avgLen = 1;

    public StationRagService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 检索最相关的若干条目；允许无命中，返回空列表。 */
    public List<Map<String, Object>> search(String query, int topK) {
        int k = Math.max(1, Math.min(8, topK));
        List<String> q = tokens(query);
        if (q.isEmpty()) {
            return List.of();
        }
        List<Entry> entries = index();
        Map<String, Integer> df = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (String token : new HashSet<>(q)) {
            for (Entry e : entries) {
                if (e.freq().containsKey(token)) {
                    df.merge(token, 1, Integer::sum);
                }
            }
        }
        int n = Math.max(1, entries.size());
        List<Map<String, Object>> scored = new ArrayList<>();
        for (Entry e : entries) {
            double score = 0;
            for (String token : new HashSet<>(q)) {
                Integer f = e.freq().get(token);
                if (f == null) {
                    continue;
                }
                int d = df.getOrDefault(token, 0);
                double idf = Math.log(1 + (n - d + 0.5) / (d + 0.5));
                double tf = f;
                score += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * e.len() / avgLen));
                if (e.tags().contains(token)) {
                    score += 0.6;
                }
            }
            if (score > 0.0001) {
                scored.add(hit(e, score));
            }
        }
        scored.sort((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")));
        return scored.size() > k ? new ArrayList<>(scored.subList(0, k)) : scored;
    }

    /** 按 id 取条目（给引用回填用）。 */
    public Map<String, Object> byId(long id) {
        for (Entry e : index()) {
            if (e.id() == id) {
                return hit(e, 0);
            }
        }
        return null;
    }

    public int size() {
        return index().size();
    }

    private Map<String, Object> hit(Entry e, double score) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("doc", e.doc());
        m.put("section", e.section());
        m.put("title", e.title());
        m.put("snippet", snippet(e.content()));
        m.put("score", Math.round(score * 100) / 100.0);
        m.put("citation", "《" + e.doc() + "》" + e.section());
        return m;
    }

    private List<Entry> index() {
        long now = System.currentTimeMillis();
        if (!index.isEmpty() && now - loadedAt.get() < TTL_MS) {
            return index;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, doc, section, title, content, tags FROM kb_entries ORDER BY id");
        List<Entry> list = new ArrayList<>();
        int totalLen = 0;
        for (Map<String, Object> r : rows) {
            String doc = str(r.get("doc"));
            String section = str(r.get("section"));
            String title = str(r.get("title"));
            String content = str(r.get("content"));
            String tags = str(r.get("tags"));
            List<String> all = new ArrayList<>();
            all.addAll(tokens(title));
            all.addAll(tokens(tags));
            all.addAll(tokens(doc));
            all.addAll(tokens(content));
            Map<String, Integer> freq = new HashMap<>();
            for (String t : all) {
                freq.merge(t, 1, Integer::sum);
            }
            totalLen += all.size();
            list.add(new Entry(((Number) r.get("id")).longValue(), doc, section, title, content,
                    new HashSet<>(tokens(tags)), freq, all.size()));
        }
        avgLen = list.isEmpty() ? 1 : Math.max(1, (double) totalLen / list.size());
        index = list;
        loadedAt.set(now);
        return list;
    }

    /** 中文按相邻两字切词，英文数字按整词切，单个汉字也保留。 */
    static List<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher m = SEGMENT.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String s = m.group();
            if (s.charAt(0) < 128) {
                out.add(s);
                continue;
            }
            if (s.length() == 1) {
                out.add(s);
                continue;
            }
            for (int i = 0; i + 1 < s.length(); i++) {
                out.add(s.substring(i, i + 2));
            }
        }
        return out;
    }

    private static String snippet(String content) {
        String s = content == null ? "" : content.trim();
        return s.length() <= 150 ? s : s.substring(0, 150) + "…";
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private record Entry(long id, String doc, String section, String title, String content,
                         Set<String> tags, Map<String, Integer> freq, int len) {
    }
}
