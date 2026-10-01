package com.tradevision.service;

import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.*;
import java.util.regex.*;

@Service
public class StockSymbolService {

    private final RestTemplate http = buildRestTemplate();

    // NSE's own bulk equity list is blocked by Akamai for Render/cloud IPs
    // (403 from errors.edgesuite.net at the edge, not from NSE itself — not
    // fixable with headers from a server IP). Yahoo Finance's search endpoint
    // is used instead: no bulk universe, but genuinely searches all NSE-listed
    // symbols Yahoo indexes, per query, live.
    public Map<String, Object> search(String query, int limit) {
        List<Map<String, String>> results = new ArrayList<>();
        String warning = null;

        if (query != null && !query.isBlank()) {
            try {
                results = searchYahoo(query, limit);
            } catch (Exception e) {
                warning = e.getMessage() != null ? e.getMessage().split("\n")[0] : "search failed";
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("results", results);
        resp.put("total", results.size());
        resp.put("stale", false);
        if (warning != null) resp.put("warning", warning);
        return resp;
    }

    private List<Map<String, String>> searchYahoo(String query, int limit) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

        String url = "https://query1.finance.yahoo.com/v1/finance/search?q="
                + java.net.URLEncoder.encode(query, "UTF-8")
                + "&quotesCount=" + limit + "&newsCount=0";

        ResponseEntity<String> resp = http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        String json = resp.getBody();
        if (json == null || json.isBlank()) return List.of();

        List<Map<String, String>> out = new ArrayList<>();
        Matcher objs = Pattern.compile("\\{[^{}]*\"exchange\"[^{}]*\\}").matcher(json);
        while (objs.find()) {
            String obj = objs.group();
            String exchange = jsonStr(obj, "exchange");
            // NSI = NSE, BSE = BSE — filter to Indian exchanges only
            if (exchange == null || !(exchange.equals("NSI") || exchange.equals("BSE"))) continue;

            String symbol = jsonStr(obj, "symbol");
            String name   = jsonStr(obj, "longname", "shortname");
            if (symbol == null || name == null) continue;

            Map<String, String> item = new LinkedHashMap<>();
            item.put("symbol", symbol.replace(".NS", "").replace(".BO", ""));
            item.put("name", name);
            out.add(item);
        }
        return out;
    }

    private String jsonStr(String obj, String... keys) {
        for (String key : keys) {
            Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(obj);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private RestTemplate buildRestTemplate() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(8000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }
}