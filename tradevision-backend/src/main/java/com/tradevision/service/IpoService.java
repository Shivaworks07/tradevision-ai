package com.tradevision.service;

import com.tradevision.model.IpoItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * Fetches live IPO data from InvestorGain's internal JSON API
 * (webnodejs.investorgain.com/cloud/v2/report/data-read/...), the same endpoint InvestorGain's
 * own frontend calls to render its table client-side. Calling that JSON API directly avoids two
 * problems a server-side HTML fetch would otherwise hit: NSE's public pages are blocked at
 * Akamai's edge for cloud datacenter IPs regardless of headers or cookies, and a page whose table
 * is rendered client-side via JavaScript (as InvestorGain's and Chittorgarh's own report pages
 * are) returns an empty shell to a server-side fetch with no rows to parse.
 *
 * <p>Status (OPEN/UPCOMING/CLOSED/LISTED) is always recomputed from the
 * ~Srt_Open/~Srt_Close/~Str_Listing ISO dates rather than trusted from any source text, since
 * dates can't silently go stale the way a cached status label can.
 */
@Service
public class IpoService {

    private static final Logger log = LoggerFactory.getLogger(IpoService.class);

    private final RestTemplate http = buildRestTemplate();

    private volatile List<IpoItem> cache = new ArrayList<>();
    private volatile long lastFetched = 0;
    private volatile String lastError = null;
    private volatile boolean initialFetchDone = false;

    private static final String API_BASE = "https://webnodejs.investorgain.com/cloud/v2/report/data-read/331/1/7/%d/%d-%02d/0/all?search=&v=21-55";

    public Map<String, Object> getIpos(String statusFilter) {
        if (!initialFetchDone) {
            fetchAll();
            initialFetchDone = true;
        }
        if (System.currentTimeMillis() - lastFetched > 300_000) {
            new Thread(this::fetchAll).start();
        }
        return buildResponse(statusFilter);
    }

    public void fetchAll() {
        try {
            List<IpoItem> fresh = fetchFromInvestorGain();

            LocalDate today = LocalDate.now();
            for (IpoItem ipo : fresh) {
                ipo.setStatus(computeStatus(ipo, today));
                ipo.setFetchedAtMs(System.currentTimeMillis());
            }
            fresh.sort(Comparator.comparing(this::sortKey));

            if (!fresh.isEmpty()) {
                cache = fresh;
                lastFetched = System.currentTimeMillis();
                lastError = null;
                log.info("[IPO] Fetched {} IPOs from InvestorGain", fresh.size());
            } else {
                lastError = "InvestorGain returned zero records";
                log.warn("[IPO] {} -- serving stale cache ({} items)", lastError, cache.size());
            }
        } catch (Exception e) {
            lastError = e.getMessage() != null ? e.getMessage().split("\n")[0] : "unknown error";
            log.warn("[IPO] Fetch failed: {} -- serving stale cache ({} items)", lastError, cache.size());
        }
    }

    private List<IpoItem> fetchFromInvestorGain() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.set("Accept", "application/json, text/plain, */*");
        headers.set("Origin", "https://www.investorgain.com");
        headers.set("Referer", "https://www.investorgain.com/");
        HttpEntity<String> entity = new HttpEntity<>(headers);

        LocalDate now = LocalDate.now();
        // InvestorGain's URL bakes in year + a "financial year" style range
        // (e.g. 2026-27); using the current calendar year for both keeps
        // this correct without a hardcoded value that goes stale next year.
        String url = String.format(API_BASE, now.getYear(), now.getYear(), (now.getYear() + 1) % 100);

        ResponseEntity<String> resp = http.exchange(url, HttpMethod.GET, entity, String.class);
        String json = resp.getBody();
        if (json == null || json.isBlank()) return List.of();

        List<IpoItem> items = new ArrayList<>();
        // Split the top-level "reportTableData":[ ... ] array into individual
        // {...} objects. Each record is a flat JSON object with no nested
        // objects inside it (only nested arrays/strings), so a brace-depth
        // scan reliably finds each record's boundaries without needing a
        // full JSON parser dependency.
        int arrStart = json.indexOf("\"reportTableData\":[");
        if (arrStart < 0) return List.of();
        int pos = arrStart + "\"reportTableData\":[".length();
        int depth = 0, recordStart = -1;
        boolean inString = false, escape = false;
        // String-aware brace scan: braces inside quoted HTML string values
        // (none observed in a real captured response, but not guaranteed
        // absent in every possible record) must not be counted as JSON
        // object boundaries. Verified this exact algorithm against a real
        // captured payload in isolation before use here.
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (inString) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inString = false;
            } else {
                if (c == '"') inString = true;
                else if (c == '{') { if (depth == 0) recordStart = pos; depth++; }
                else if (c == '}') {
                    depth--;
                    if (depth == 0 && recordStart >= 0) {
                        String record = json.substring(recordStart, pos + 1);
                        IpoItem ipo = parseRecord(record);
                        if (ipo != null) items.add(ipo);
                        recordStart = -1;
                    }
                } else if (c == ']' && depth == 0) {
                    break; // end of reportTableData array
                }
            }
            pos++;
        }
        return items;
    }

    private IpoItem parseRecord(String r) {
        String nameHtml = jsonStr(r, "~ipo_name");
        if (nameHtml == null || nameHtml.isBlank()) return null;

        String category = jsonStr(r, "~IPO_Category"); // "IPO" or "SME"
        String nameBlock = jsonStr(r, "Name"); // contains the exchange badge text
        String exchange = "Mainboard";
        if (nameBlock != null) {
            if (nameBlock.contains("NSE SME")) exchange = "NSE SME";
            else if (nameBlock.contains("BSE SME")) exchange = "BSE SME";
            else if ("SME".equals(category)) exchange = "SME";
        }

        Double price = jsonNum(r, "Price (₹)");
        Double gmp   = extractGmpValue(jsonStr(r, "GMP")); // null if "--" (no active GMP)
        Double gmpPct = null;
        if (gmp != null && price != null && price > 0) {
            gmpPct = Math.round((gmp / price) * 1000.0) / 10.0;
        }

        IpoItem ipo = new IpoItem();
        ipo.setCompany(nameHtml.trim());
        ipo.setSymbol(toSymbol(nameHtml));
        ipo.setPriceMin(price != null ? price : 0);
        ipo.setPriceMax(price != null ? price : 0);
        ipo.setGmp(gmp);
        ipo.setGmpPct(gmpPct);
        // ISO dates — already unambiguous, no year-inference needed unlike
        // the short "D-Mon" display strings elsewhere in this same payload.
        ipo.setOpenDate(jsonStr(r, "~Srt_Open"));
        ipo.setCloseDate(jsonStr(r, "~Srt_Close"));
        ipo.setListingDate(jsonStr(r, "~Str_Listing"));
        ipo.setLotSize((int) safeDouble(jsonStr(r, "Lot")));
        ipo.setCategory(category != null ? category : "");
        ipo.setIssueSize(cleanIssueSize(jsonStr(r, "IPO Size")));
        ipo.setExchange(exchange);
        ipo.setSource("InvestorGain");
        return ipo;
    }

    // ── Status: always computed, never trusted from source text ──
    private String computeStatus(IpoItem ipo, LocalDate today) {
        LocalDate open    = parseIsoDate(ipo.getOpenDate());
        LocalDate close   = parseIsoDate(ipo.getCloseDate());
        LocalDate listing = parseIsoDate(ipo.getListingDate());

        if (listing != null && !today.isBefore(listing)) return "LISTED";
        if (close   != null && today.isAfter(close))      return "CLOSED";
        if (open    != null && close != null
                && !today.isBefore(open) && !today.isAfter(close)) return "OPEN";
        if (open    != null && today.isBefore(open))      return "UPCOMING";
        return "UNKNOWN";
    }

    private LocalDate parseIsoDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return LocalDate.parse(raw.trim()); } catch (DateTimeParseException e) { return null; }
    }

    private String sortKey(IpoItem ipo) {
        int rank = switch (ipo.getStatus()) {
            case "OPEN" -> 0; case "UPCOMING" -> 1; case "CLOSED" -> 2; case "LISTED" -> 3; default -> 4;
        };
        return rank + "_" + (ipo.getOpenDate() != null ? ipo.getOpenDate() : "9999-99-99");
    }

    private Map<String, Object> buildResponse(String statusFilter) {
        List<IpoItem> filtered = cache;
        if (statusFilter != null && !statusFilter.isBlank() && !"ALL".equalsIgnoreCase(statusFilter)) {
            filtered = cache.stream()
                    .filter(i -> statusFilter.equalsIgnoreCase(i.getStatus()))
                    .collect(Collectors.toList());
        }
        boolean stale = System.currentTimeMillis() - lastFetched > 900_000;
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ipos",         filtered);
        resp.put("total",        filtered.size());
        resp.put("lastUpdated",  lastFetched);
        resp.put("stale",        stale);
        if (lastError != null) resp.put("warning", lastError);
        resp.put("gmpDisclaimer", "GMP is unofficial grey-market data, not a regulated figure — for reference only.");
        return resp;
    }

    // ── parsing helpers ────────────────────────────────────────
    // Extracts the numeric GMP value from HTML like:
    //   "&#8377;<b>25</b> (19.69%)<br>..."   → 25.0
    //   "&#8377;<b>--</b> (0.00%)<br>..."    → null (no active GMP, not zero)
    private Double extractGmpValue(String gmpHtml) {
        if (gmpHtml == null) return null;
        Matcher m = Pattern.compile("<b>(-?[\\d.]+|--)</b>").matcher(gmpHtml);
        if (!m.find()) return null;
        String val = m.group(1);
        if (val.equals("--")) return null;
        try { return Double.parseDouble(val); } catch (Exception e) { return null; }
    }

    private String cleanIssueSize(String raw) {
        if (raw == null) return "";
        // "&#8377;166.80 Cr" → "₹166.80 Cr"
        return raw.replace("&#8377;", "₹").trim();
    }

    private double safeDouble(String s) {
        if (s == null) return 0;
        try { return Double.parseDouble(s.replaceAll("[^0-9.]", "")); } catch (Exception e) { return 0; }
    }

    private String toSymbol(String company) {
        String clean = company.replaceAll("[^A-Za-z ]", "").trim().toUpperCase().replaceAll("\\s+", "");
        return clean.substring(0, Math.min(10, clean.length()));
    }

    // Simple flat-JSON string/number extraction — the API response has no
    // nested objects within a record (only nested HTML-string values), so a
    // regex-per-key lookup is reliable here without a full JSON library.
    private String jsonStr(String obj, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(obj);
        if (m.find()) return m.group(1).replace("\\\"", "\"").replace("\\/", "/");
        return null;
    }

    private Double jsonNum(String obj, String key) {
        String s = jsonStr(obj, key);
        if (s == null) return null;
        try { return Double.parseDouble(s.replaceAll("[^0-9.-]", "")); } catch (Exception e) { return null; }
    }

    private RestTemplate buildRestTemplate() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(8000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }
}