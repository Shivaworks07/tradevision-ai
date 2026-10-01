package com.tradevision.service;

import com.tradevision.model.NewsItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class NewsService {

    // P2-17 fix ("20x System.out.println, PII in logs, no correlation IDs" -- external review,
    // full context in this codebase's own new CorrelationIdFilter javadoc): no PII here (public
    // feed names and article counts only).
    private static final Logger log = LoggerFactory.getLogger(NewsService.class);

    private final RestTemplate http = buildRestTemplate();

    private final Map<String, List<NewsItem>> cache = new ConcurrentHashMap<>();
    private volatile long lastFetched = 0;
    private volatile boolean initialFetchDone = false;

    // Only fetch when requested (lazy), not on schedule
    public Map<String, Object> getNews(String category, int limit) {
        if (!initialFetchDone) {
            fetchAll();
            initialFetchDone = true;
        }
        // Refresh if cache is older than 5 minutes
        if (System.currentTimeMillis() - lastFetched > 300_000) {
            new Thread(this::fetchAll).start();
        }
        return buildResponse(category, limit);
    }

    public void fetchAll() {
        Map<String, List<NewsItem>> fresh = new ConcurrentHashMap<>();
        for (FeedSource feed : FEEDS) {
            try {
                List<NewsItem> items = fetchFeed(feed);
                fresh.computeIfAbsent(feed.category(), k -> new ArrayList<>()).addAll(items);
            } catch (Exception e) {
                log.warn("[News] Failed: {} - {}", feed.sourceName(), e.getMessage() != null ? e.getMessage().split("\n")[0] : "unknown error");
            }
        }
        fresh.forEach((cat, items) -> {
            items.sort(Comparator.comparingLong(NewsItem::getPublishedMs).reversed());
            cache.put(cat, items.stream().limit(50).collect(Collectors.toList()));
        });
        lastFetched = System.currentTimeMillis();
        int total = fresh.values().stream().mapToInt(List::size).sum();
        log.info("[News] Fetched {} articles", total);
    }

    private Map<String, Object> buildResponse(String category, int limit) {
        List<NewsItem> items;
        if ("ALL".equalsIgnoreCase(category) || category == null || category.isBlank()) {
            items = cache.values().stream()
                .flatMap(Collection::stream)
                .sorted(Comparator.comparingLong(NewsItem::getPublishedMs).reversed())
                .limit(limit).collect(Collectors.toList());
        } else {
            items = cache.getOrDefault(category.toUpperCase(), List.of())
                .stream().limit(limit).collect(Collectors.toList());
        }
        return Map.of(
            "articles",    items,
            "total",       items.size(),
            "category",    category != null ? category : "ALL",
            "lastUpdated", lastFetched,
            "categories",  List.of("ALL","CRYPTO","STOCKS","FOREX","IPO","GOLD","GLOBAL")
        );
    }

    // ── Working RSS feeds (verified) ─────────────────────────
    private static final List<FeedSource> FEEDS = List.of(
        // CRYPTO - reliable feeds
        new FeedSource("https://cointelegraph.com/rss",                                                      "CoinTelegraph", "CRYPTO"),
        new FeedSource("https://cryptonews.com/news/feed/",                                                  "CryptoNews",    "CRYPTO"),
        new FeedSource("https://decrypt.co/feed",                                                            "Decrypt",       "CRYPTO"),
        new FeedSource("https://bitcoinmagazine.com/.rss/full/",                                             "Bitcoin Magazine","CRYPTO"),

        // INDIAN STOCKS - Economic Times (works well server-side)
        new FeedSource("https://economictimes.indiatimes.com/markets/stocks/rssfeeds/2146842.cms",           "Economic Times","STOCKS"),
        new FeedSource("https://economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms",               "ET Markets",    "STOCKS"),
        new FeedSource("https://economictimes.indiatimes.com/markets/midcap/rssfeeds/1052732854.cms",        "ET Midcap",     "STOCKS"),

        // FOREX - working feeds
        new FeedSource("https://economictimes.indiatimes.com/markets/forex/rssfeeds/1349232121.cms",         "ET Forex",      "FOREX"),
        new FeedSource("https://www.dailyfx.com/feeds/forex-market-news",                                    "DailyFX",       "FOREX"),
        new FeedSource("https://www.fxstreet.com/rss/news",                                                  "FXStreet",      "FOREX"),

        // IPO - working feeds
        new FeedSource("https://economictimes.indiatimes.com/markets/ipos/fpos/rssfeeds/1052732854.cms",     "ET IPO",        "IPO"),
        new FeedSource("https://economictimes.indiatimes.com/small-biz/sme-sector/rssfeeds/6048685.cms",     "ET SME IPO",    "IPO"),

        // GOLD / COMMODITIES
        new FeedSource("https://economictimes.indiatimes.com/markets/commodities/rssfeeds/1052732871.cms",   "ET Commodities","GOLD"),
        new FeedSource("https://www.investing.com/rss/news_304.rss",                                         "Investing Gold","GOLD"),

        // GLOBAL - working feeds
        new FeedSource("https://feeds.content.dowjones.io/public/rss/mw_realtimeheadlines",                  "MarketWatch",   "GLOBAL"),
        new FeedSource("https://www.investing.com/rss/news_25.rss",                                          "Investing.com", "GLOBAL"),
        new FeedSource("https://rss.app/feeds/your-business-rss.xml",                                        "Business News", "GLOBAL")
    );

    record FeedSource(String url, String sourceName, String category) {}

    private List<NewsItem> fetchFeed(FeedSource feed) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (compatible; TradeVisionAI/1.0; +https://tradevision-7fmo.onrender.com)");
        headers.set("Accept", "application/rss+xml, application/xml, text/xml, */*");
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<String> resp = http.exchange(feed.url(), HttpMethod.GET, entity, String.class);
        String xml = resp.getBody();
        if (xml == null || xml.isBlank()) return List.of();

        // Clean XML
        xml = xml.trim();
        if (!xml.startsWith("<")) {
            int start = xml.indexOf('<');
            if (start < 0) return List.of();
            xml = xml.substring(start);
        }

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setNamespaceAware(false);
        var doc = dbf.newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        NodeList items = doc.getElementsByTagName("item");
        List<NewsItem> result = new ArrayList<>();

        for (int i = 0; i < Math.min(items.getLength(), 20); i++) {
            Element item = (Element) items.item(i);
            String title   = text(item, "title");
            String link    = text(item, "link");
            String desc    = stripHtml(text(item, "description"));
            String pubDate = text(item, "pubDate");
            String img     = extractImage(item);

            if (title.isBlank() || link.isBlank()) continue;

            result.add(new NewsItem(
                hash(link), title.trim(), desc, link.trim(),
                feed.sourceName(), feed.category(),
                pubDate.trim(), img, parseDate(pubDate)
            ));
        }
        return result;
    }

    private String text(Element el, String tag) {
        NodeList nl = el.getElementsByTagName(tag);
        if (nl.getLength() == 0) return "";
        Node n = nl.item(0);
        return n != null && n.getTextContent() != null ? n.getTextContent().trim() : "";
    }

    private String stripHtml(String html) {
        if (html == null || html.isBlank()) return "";
        String s = html.replaceAll("<[^>]*>","").replaceAll("&amp;","&")
            .replaceAll("&lt;","<").replaceAll("&gt;",">").replaceAll("&nbsp;"," ")
            .replaceAll("&#\\d+;","").replaceAll("\\s+"," ").trim();
        return s.length() > 220 ? s.substring(0,220)+"…" : s;
    }

    private String extractImage(Element item) {
        String[] tags = {"media:content","media:thumbnail","enclosure"};
        for (String t : tags) {
            NodeList nl = item.getElementsByTagName(t);
            if (nl.getLength() > 0) {
                String url = ((Element)nl.item(0)).getAttribute("url");
                if (!url.isBlank() && (url.contains(".jpg")||url.contains(".png")||url.contains(".webp")||url.contains(".jpeg")))
                    return url;
            }
        }
        return "";
    }

    private long parseDate(String d) {
        if (d == null || d.isBlank()) return System.currentTimeMillis();
        try { return ZonedDateTime.parse(d.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli(); }
        catch (Exception e) { return System.currentTimeMillis(); }
    }

    private String hash(String input) {
        try {
            byte[] b = MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString().substring(0,12);
        } catch (Exception e) { return String.valueOf(Math.abs(input.hashCode())); }
    }

    private RestTemplate buildRestTemplate() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(8000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }
}
