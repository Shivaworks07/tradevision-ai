package com.tradevision.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Review finding ("1m trading still isn't truly event-driven" -- external review, twentieth
 * pass, P1, confirmed real by direct inspection: AutonomousScannerService.scan() runs on a
 * @Scheduled(fixedDelay = 60_000) cycle regardless of what timeframe a plan is actually
 * configured for -- for a 1m strategy specifically, a real candle can close and this
 * application may not act on it for up to a full minute, long enough to miss the intended
 * entry): the actual fix, following the review's own suggested architecture exactly:
 *
 *   Binance kline WebSocket -> closed candle event -> relevant 1m plans -> strategy
 *
 * DESIGN CHOICE, stated plainly, same as BinanceUserDataStreamService's own: this stream is used
 * PURELY as a "a real 1m candle just closed, check this symbol now" trigger. It does NOT parse
 * OHLCV out of the stream and feed it directly into analysis -- on a closed-candle event, this
 * calls AutonomousScannerService.scanOneSymbol() directly, the exact same method the 60s poll
 * already calls, which does its own REST candle fetch and runs the exact same, already-hardened
 * analysis pipeline. That keeps exactly one code path responsible for actually deciding whether
 * to trade, whether it's woken by this push signal or by the periodic poll that remains as a
 * backstop (REST is fallback/reconciliation, per the review's own suggested design -- nothing
 * about the existing scheduled scan() was removed by this fix). The value this adds is latency
 * for the specific case that matters (1m plans, on their own explicitly-configured/TIER1
 * symbols -- see AutonomousScannerService.compute1mScanTargets's own HONEST SCOPE note for what
 * this deliberately does not cover), not a second source of trading decisions.
 *
 * This is a PUBLIC, unauthenticated Binance market-data stream -- confirmed directly against
 * Binance's own current documentation before writing this, not assumed: no API key, secret, or
 * per-credential connection is needed at all. One shared connection covers every credential's
 * own 1m plans simultaneously, subscribed to whatever distinct symbol set those plans actually
 * need right now.
 *
 * HONEST CAVEAT, same as every other Binance-facing file this session: this sandbox has no
 * network path to binance.com or testnet.binance.vision. The endpoint URL, combined-stream
 * format, and kline payload shape below were verified against Binance's current published
 * documentation via live web search moments before writing this -- but the actual connection,
 * message flow, and reconnect behavior have never been exercised against a real server. Test
 * against a real connection (does a real closed-1m-candle event actually arrive and trigger
 * scanOneSymbol; does reconnection actually recover after a network blip; does the 24-hour
 * server-side disconnect get handled gracefully) before this is trusted for LIVE trading.
 */
@Service
@RequiredArgsConstructor
public class Kline1mStreamService {

    private static final Logger log = LoggerFactory.getLogger(Kline1mStreamService.class);

    // Review finding, same verification discipline as BinanceUserDataStreamService's own base
    // URLs: confirmed against Binance's current published WebSocket Streams documentation.
    // Public market data only -- the same base serves both TESTNET and LIVE identically (unlike
    // the user-data stream, this has no account-specific auth/mode split at all).
    private static final String BASE_URL = "wss://stream.binance.com:9443/stream?streams=";

    private final AutonomousScannerService scannerService;
    private final com.tradevision.config.ShutdownState shutdownState;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile WebSocket currentSocket;
    private volatile Set<String> subscribedSymbols = Set.of();
    /** Rebuilt on every reconciliation pass -- the current answer to "which
     *  (profile, credential, adapter, plan) tuples care about this symbol closing right now". */
    private volatile Map<String, List<AutonomousScannerService.OneMinuteScanTarget>> routingBySymbol = Map.of();

    /**
     * Review finding, same context as this class's own top-level javadoc: reconciles the
     * WebSocket's own subscription set against whatever 1m plans currently need one, every 2
     * minutes -- frequent enough that a newly-enabled 1m plan starts getting real-time triggers
     * quickly, infrequent enough not to reconnect constantly under normal operation (the
     * required symbol set only changes when a plan is created/edited/disabled, not every cycle).
     */
    @Scheduled(fixedDelay = 120_000, initialDelay = 50_000, scheduler = "scanScheduler")
    public void reconcileSubscriptions() {
        if (shutdownState.isShuttingDown()) return;
        List<AutonomousScannerService.OneMinuteScanTarget> targets;
        try {
            targets = scannerService.compute1mScanTargets();
        } catch (Exception e) {
            log.warn("Could not compute 1m scan targets this pass ({}) -- leaving the existing kline subscription (if any) as-is.", e.getMessage());
            return;
        }

        Map<String, List<AutonomousScannerService.OneMinuteScanTarget>> newRouting = new java.util.HashMap<>();
        for (var target : targets) {
            newRouting.computeIfAbsent(target.symbol().toUpperCase(), k -> new java.util.ArrayList<>()).add(target);
        }
        Set<String> requiredSymbols = newRouting.keySet();
        routingBySymbol = newRouting; // always current, independent of whether a reconnect happens below

        if (requiredSymbols.equals(subscribedSymbols)) return; // no change in what needs to be subscribed to

        closeCurrentSocket("Resubscribing: the required 1m symbol set changed.");
        if (requiredSymbols.isEmpty()) {
            subscribedSymbols = Set.of();
            log.info("Kline stream: no active 1m plans right now -- no connection needed.");
            return;
        }
        connect(requiredSymbols);
    }

    private void connect(Set<String> symbols) {
        String streamParams = symbols.stream()
            .map(s -> s.toLowerCase() + "@kline_1m")
            .reduce((a, b) -> a + "/" + b)
            .orElse("");
        URI uri = URI.create(BASE_URL + streamParams);
        Listener listener = new Listener();
        CompletableFuture<WebSocket> future = httpClient.newWebSocketBuilder().buildAsync(uri, listener);
        future.thenAccept(socket -> {
            currentSocket = socket;
            subscribedSymbols = Set.copyOf(symbols);
            log.info("Kline stream connected for {} 1m symbol(s): {}", symbols.size(), symbols);
        }).exceptionally(e -> {
            log.warn("Kline stream connection failed ({}) -- will retry on the next reconciliation pass.", e.getMessage());
            subscribedSymbols = Set.of(); // so the next pass genuinely retries rather than believing this succeeded
            return null;
        });
    }

    private void closeCurrentSocket(String reason) {
        WebSocket socket = currentSocket;
        currentSocket = null;
        if (socket != null) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, reason);
            } catch (Exception e) {
                log.debug("Kline stream: error closing previous socket (non-fatal): {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void closeStream() {
        closeCurrentSocket("Application shutting down.");
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            webSocket.request(1);
            if (!last) return null;
            String message = buffer.toString();
            buffer.setLength(0);
            try {
                handleMessage(message);
            } catch (Exception e) {
                log.warn("Kline stream: could not process message ({}): {}", e.getMessage(), message);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("Kline stream connection error: {} -- will reconnect on the next reconciliation pass.", error.getMessage());
            currentSocket = null;
            subscribedSymbols = Set.of(); // force a genuine reconnect attempt next pass, not a no-op
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("Kline stream connection closed (status {}, reason: {}) -- will reconnect on the next reconciliation pass if still needed.", statusCode, reason);
            currentSocket = null;
            subscribedSymbols = Set.of();
            return null;
        }
    }

    /**
     * Review finding, same context as this class's own top-level javadoc: parses only what's
     * needed to decide whether a real trigger fired -- symbol and the closed-candle flag (`x`)
     * -- and never touches OHLCV values from the stream at all (see this class's own DESIGN
     * CHOICE note for why: scanOneSymbol does its own, independent REST fetch of the real candle
     * series, so nothing here is ever trusted as trading-relevant market data by itself).
     */
    private void handleMessage(String message) throws Exception {
        JsonNode root = mapper.readTree(message);
        JsonNode data = root.path("data");
        if (!"kline".equals(data.path("e").asText(null))) return;
        JsonNode k = data.path("k");
        if (!k.path("x").asBoolean(false)) return; // not yet closed -- ignore, same as every other consumer of this stream must
        String symbol = k.path("s").asText(null);
        if (symbol == null) return;

        var targets = routingBySymbol.get(symbol.toUpperCase());
        if (targets == null || targets.isEmpty()) return; // stale subscription from a symbol set that's since shrunk -- harmless
        for (var target : targets) {
            try {
                scannerService.scanOneSymbol(target.profile(), target.credential(), target.adapter(), target.symbol(), target.plan());
            } catch (Exception e) {
                log.warn("Kline-triggered scan failed for {} (plan {}, credential {}): {}",
                    symbol, target.plan().getId(), target.credential().getId(), e.getMessage());
            }
        }
    }
}
