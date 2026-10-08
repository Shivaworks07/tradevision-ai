package com.tradevision.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerMode;
import com.tradevision.model.BrokerType;
import com.tradevision.repository.BrokerCredentialRepository;
import com.tradevision.repository.RiskProfileRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains a live connection to Binance's user-data WebSocket API, built via the current method
 * after verifying live against Binance's own docs. The old listenKey + `wss://stream.binance.com/ws/`
 * approach was deprecated in April 2025 and its REST endpoints were retired 2026-02-20. This uses
 * the replacement instead: connect to the WebSocket API (`wss://ws-api.binance.com:443/ws-api/v3`,
 * or the testnet equivalent), authenticate with `userDataStream.subscribe.signature` using the
 * account's ordinary HMAC key (no Ed25519 needed — that's only required for `session.logon`,
 * which this deliberately does not use, since this whole system's credential model only stores
 * HMAC key/secret pairs).
 *
 * DESIGN CHOICE, stated plainly: incoming events are mostly not parsed for order/fill details and
 * acted on directly -- they're primarily used as a "something changed, check now" trigger that
 * calls PositionMonitorService.reconcileCredential(), the same REST-based reconciliation logic
 * that remains the sole mechanism for Fill Ledger, Position, and P&L. One narrow exception exists
 * (see applyExecutionReportFastPath's own method javadoc): executionReport events are parsed and
 * applied directly to this application's own local Order record via OrderService's own
 * atomic-conditional-update methods, the same ones the synchronous REST response from
 * adapter.placeOrder() already uses. reconcileCredential() is still called immediately on every
 * relevant event (executionReport/listStatus/outboundAccountPosition), not left to the 60-second
 * poll backstop alone. That keeps exactly one code path responsible for computing P&L/fees/exit
 * prices, whether it's woken up by this push signal or by the 60-second poll that remains as a
 * backstop. The value this adds is latency (seconds instead of up to 60s), not a second source of
 * truth.
 *
 * HONEST CAVEAT, same as every other Binance-facing file: this sandbox has no network path to
 * binance.com or testnet.binance.vision. The endpoint URLs and message formats below were
 * verified against Binance's current published documentation via live web search moments before
 * writing this — but the actual connection, handshake, and event flow have never been exercised
 * against a real server.
 *
 * Before this file is trusted for LIVE trading, test all of this against Spot Testnet:
 *   - connect + subscribe (initial handshake succeeds, subscribe.signature confirms the
 *     subscription itself -- see this class's own top-level javadoc for why this codebase uses
 *     the newer WebSocket API, not a listenKey)
 *   - executionReport NEW, PARTIALLY_FILLED, FILLED, CANCELED (each triggers reconciliation
 *     correctly and PositionMonitorService resolves the right position/order)
 *   - OCO listStatus events (both legs, partial fills, ALL_DONE with and without a filled leg)
 *   - disconnect (network drop) — confirm reconnection actually happens, not just that the code
 *     path exists
 *   - reconnect after a drop — confirm no duplicate/missed events during the gap, and that the
 *     60-second REST poll backstop actually catches whatever the WebSocket gap might have missed
 *   - subscribe failure and subscribe timeout — confirm the connection is actually torn down and
 *     reconnected, not left as a zombie entry in `connections`
 *   - 24-hour connection refresh (this codebase's own connectedAt-based proactive reconnect, NOT
 *     a listenKey expiry -- there is no listenKey in this implementation at all; confirm the
 *     refresh logic actually fires before Binance's own ~24-hour connection limit in a real
 *     long-running process, not just in code review)
 *   - server restart mid-connection (confirm ShutdownState's graceful-shutdown behavior doesn't
 *     leave a half-closed WebSocket, and that a fresh connection is established on the next
 *     startup without needing a manual restart)
 */
@Service
@RequiredArgsConstructor
public class BinanceUserDataStreamService {

    private static final Logger log = LoggerFactory.getLogger(BinanceUserDataStreamService.class);

    private static final String LIVE_WS_API = "wss://ws-api.binance.com:443/ws-api/v3";
    private static final String TESTNET_WS_API = "wss://ws-api.testnet.binance.vision/ws-api/v3";
    // Binance documents a single WS API connection as valid for 24h before a serverShutdown/
    // disconnect; reconnect proactively before that rather than waiting to be dropped.
    private static final long RECONNECT_BEFORE_EXPIRY_MS = 23 * 60 * 60 * 1000L;
    /**
     * The connection's state machine, scoped to the transitions this class genuinely knows about
     * and can observe -- every value here maps to one real, existing code path, not an
     * aspiration. A DEGRADED value is deliberately not modeled: giving it real meaning would need
     * an activity/heartbeat watchdog that correlates "no messages" against "should there have
     * been activity" (a quiet account with no trades legitimately has none), and this codebase
     * has deliberately declined to build that without real production traffic to validate the
     * correlation against -- adding an unused DEGRADED value would imply a monitoring capability
     * that doesn't actually exist.
     */
    enum ConnectionState { RAW_CONNECTED, SUBSCRIBING, SUBSCRIBED, CLOSED }
    /**
     * How long to wait for Binance's own subscribe confirmation. See
     * Listener.pendingSubscribeAt's own field comment. 10 seconds is comfortably longer than a
     * genuine subscribe round-trip should ever take (this is a single small WebSocket message,
     * not a REST call under load), short enough that a genuinely silent Binance response is
     * caught well within one reconcileConnections sweep.
     */
    private static final long SUBSCRIBE_TIMEOUT_MS = 10_000L;
    /**
     * A message-staleness watchdog (lastEventTimeMs) is deliberately not used to detect a hung
     * connection -- a quiet account with no trades legitimately has no messages, and this
     * codebase has declined to build the activity-correlation logic needed to tell "quiet" apart
     * from "dead" without real production traffic to validate it against. That limitation does
     * not apply here: verifySubscriptionHealth's own session.status probe is an active
     * request/response check, not a passive message count -- Binance answers it regardless of
     * whether the account has traded, so "this connection never responds to an explicit health
     * probe, repeatedly" is unambiguous dead-connection evidence with no quiet-account
     * false-positive risk. If the previous session.status request for a connection is still
     * unanswered (pendingSessionStatusId still set) when the next scheduled check runs, that's
     * one full 5-minute cycle of total silence to an active probe -- this many consecutive
     * misses (2 -> ~10 minutes) is treated as a hung connection that onClose/onError will never
     * fire for on its own, and is force-closed so the existing reconcileConnections sweep
     * reopens it through the same backoff-governed path already used for
     * onClose/onError/subscribe-timeout -- no new reconnection mechanism, just a new trigger
     * into the one that already exists.
     */
    private static final int MAX_CONSECUTIVE_MISSED_SESSION_STATUS = 2;

    private final BrokerCredentialRepository credentialRepo;
    private final RiskProfileRepository riskProfileRepo;
    private final BrokerCredentialService credentialService;
    private final PositionMonitorService positionMonitorService;
    /**
     * Applies a real fill directly to the local OMS Order record the moment Binance pushes it,
     * rather than only ever discovering it via the next REST reconciliation pass. See this
     * class's own executionReport-handling comment.
     */
    private final OrderService orderService;
    private final com.tradevision.repository.OrderRepository orderRepo;
    private final com.tradevision.config.ShutdownState shutdownState;
    private final ExchangeHealthService exchangeHealthService;
    /**
     * Field name matches the "wsReconcileDispatchScheduler" bean name exactly (see
     * SchedulingConfig's own javadoc for that bean) -- Spring resolves it by name since multiple
     * TaskScheduler beans exist in this application, the same implicit-by-name-match convention
     * every other multi-bean-of-the-same-type field in this codebase relies on (no @Qualifier
     * needed or used anywhere in this codebase). Used to dispatch reconciliation off the socket
     * thread -- see Listener.onText's own comment and scheduleDebouncedReconcile's own javadoc
     * below.
     */
    private final org.springframework.scheduling.TaskScheduler wsReconcileDispatchScheduler;
    /**
     * Tracks which credentials already have a debounced reconciliation dispatch scheduled but
     * not yet started. A burst of N events (executionReport/listStatus/
     * outboundAccountPosition) for the same credential in quick succession must collapse into a
     * single dispatch, not N: the first event in a burst adds this credential's id here and
     * schedules the actual reconcile RECONCILE_DEBOUNCE_MS in the future; every subsequent event
     * for that same credential while it's still pending simply finds it already present and does
     * nothing further -- reconcileCredential() itself always reads the latest broker/local state
     * when it finally runs, so nothing is lost by coalescing, only the redundant extra REST round
     * trips a naive "fire on every event" design would make. The id is removed the moment the
     * scheduled task actually starts (not when it finishes) -- see
     * scheduleDebouncedReconcile's own javadoc for why that specific timing matters.
     */
    private final java.util.Set<String> pendingWsReconciles = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Long enough to genuinely coalesce a tight burst of events for the same credential (Binance
     * can emit several executionReport messages within milliseconds of each other for one active
     * order lifecycle), short enough that a real fill is still reflected in this application's
     * own reconciled state well within a human-imperceptible delay -- the existing 60-second
     * scheduled reconciliation poll remains the actual authoritative safety net regardless of
     * this debounce window's exact value.
     */
    private static final long RECONCILE_DEBOUNCE_MS = 500L;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, ManagedConnection> connections = new ConcurrentHashMap<>();
    // Per-credential backoff state. See reconcileConnections' own javadoc. consecutiveFailures
    // drives the exponential delay (30s, 60s, 120s, 240s, capped at 300s). nextRetryAllowedAt is
    // the actual "skip until" timestamp reconcileConnections checks.
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private final Map<String, java.time.Instant> nextRetryAllowedAt = new ConcurrentHashMap<>();
    private static final long MAX_BACKOFF_MS = 300_000;

    /** Resets the failure count on a genuine success -- a credential that reconnects cleanly
     *  after one bad attempt shouldn't carry a stale, growing delay forward from before. See
     *  reconcileConnections' own javadoc. */
    private void recordConnectSuccess(String credentialId) {
        consecutiveFailures.remove(credentialId);
        nextRetryAllowedAt.remove(credentialId);
    }

    /** Each call doubles the delay from the last (30s, 60s, 120s, 240s...), capped at
     *  MAX_BACKOFF_MS so a persistently-failing credential's wait stays bounded, while a
     *  credential that recovers quickly isn't held back. See reconcileConnections' own javadoc. */
    private void recordConnectFailure(String credentialId) {
        int failures = consecutiveFailures.merge(credentialId, 1, Integer::sum);
        long delayMs = Math.min(30_000L * (1L << Math.min(failures - 1, 4)), MAX_BACKOFF_MS);
        nextRetryAllowedAt.put(credentialId, Instant.now().plusMillis(delayMs));
    }

    private record ManagedConnection(WebSocket socket, Instant connectedAt, Listener listener) {}

    /**
     * False for a credential with no connection at all, a connection still awaiting Binance's
     * own subscribe confirmation, or a connection whose subscribe request already failed (closed
     * immediately rather than left as a zombie -- so "exists in map" and "subscribed" are not
     * conflated). See Listener.state's own field comment and ConnectionState's own class-level
     * comment.
     */
    public boolean isSubscribed(String credentialId) {
        return getConnectionState(credentialId) == ConnectionState.SUBSCRIBED;
    }

    /**
     * The full state, not just the collapsed "subscribed or not" boolean -- null for a
     * credential with no connection in `connections` at all (distinct from every real state a
     * connection can be in once one exists). See ConnectionState's own class-level comment.
     */
    public ConnectionState getConnectionState(String credentialId) {
        ManagedConnection existing = connections.get(credentialId);
        return existing == null ? null : existing.listener().state;
    }

    /**
     * The most recent event time actually observed for this credential's stream, or 0 if none
     * has arrived yet (or there's no connection at all) -- exposed for observability, same as
     * getConnectionState() above. See Listener.lastEventTimeMs's own field comment.
     */
    public long getLastEventTimeMs(String credentialId) {
        ManagedConnection existing = connections.get(credentialId);
        return existing == null ? 0L : existing.listener().lastEventTimeMs;
    }

    /** Establish streams for every eligible credential as soon as the app is up. */
    @EventListener(ApplicationReadyEvent.class)
    public void connectAllOnStartup() {
        log.info("Establishing Binance user-data-stream connections for eligible credentials.");
        reconcileConnections();
    }

    /** Every 30 seconds: open streams for newly-eligible credentials, close streams for
     *  credentials that stopped qualifying, and proactively refresh anything close to the 24h
     *  connection-age limit. This is the mechanism that reacts to a credential's auto-trade
     *  being turned on/off — there's no direct hook into RiskProfileService for this pass.
     *
     * The 30-second interval keeps the worst-case gap small for the common case (a clean
     * disconnect that reconnects fine on the very next sweep). A per-credential exponential
     * backoff is layered on top for a repeatedly-failing credential specifically, via
     * consecutiveFailures/nextRetryAllowedAt below -- deliberately implemented as "skip this
     * sweep" rather than a separate scheduled retry task, to avoid two different reconnection
     * mechanisms racing each other -- reusing the existing sweep as the single retry clock, not
     * adding a second one. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000, scheduler = "scanScheduler")
    public void reconcileConnections() {
        // This sweep is exactly the mechanism that would reopen a connection onClose() just tore
        // down as part of graceful shutdown, so it must be gated the same way every other
        // periodic worker in this codebase already is via ShutdownState.
        if (shutdownState.isShuttingDown()) return;
        for (BrokerCredential credential : credentialRepo.findAll()) {
            // mode must be checked explicitly here, not just isActive()/autoTradeEnabled/
            // tradingHalted -- otherwise a PAPER credential with auto-trade enabled would be
            // considered eligible for a real WebSocket connection attempt to Binance's testnet
            // endpoint below (this class's own mode==LIVE ? LIVE_WS_API : TESTNET_WS_API has no
            // PAPER branch at all). PAPER never needs or should attempt any real network
            // connection to Binance -- its own position/order management already works entirely
            // through
            // PositionMonitorService's REST-reconciliation polling against PaperBrokerAdapter's
            // simulated getOrderStatus/getOcoStatus, which needs no stream at all.
            boolean eligible = credential.isActive()
                && credential.getMode() != BrokerMode.PAPER
                && riskProfileRepo.findByCredentialId(credential.getId())
                    .map(p -> p.isAutoTradeEnabled() && !p.isTradingHalted())
                    .orElse(false);

            ManagedConnection existing = connections.get(credential.getId());
            boolean expiringSoon = existing != null
                && existing.connectedAt().isBefore(Instant.now().minusMillis(RECONNECT_BEFORE_EXPIRY_MS));

            // Checked before the eligible/expiringSoon branches below, since a timed-out
            // subscription must be torn down regardless of eligibility -- closing it here
            // (rather than folding the condition into those branches) means `existing` is
            // correctly null by
            // the time this same sweep reaches them, so the ordinary "eligible && existing ==
            // null" reconnect path below handles the actual retry naturally, on this exact pass
            // if the backoff already elapsed, or a later one otherwise.
            if (existing != null && existing.listener().pendingSubscribeId != null
                    && existing.listener().pendingSubscribeAt != null
                    && existing.listener().pendingSubscribeAt.isBefore(Instant.now().minusMillis(SUBSCRIBE_TIMEOUT_MS))) {
                log.warn("User-data-stream subscribe request for credential {} received no response within {}ms -- treating as failed "
                    + "and reconnecting.", credential.getId(), SUBSCRIBE_TIMEOUT_MS);
                exchangeHealthService.recordWsError(credential.getId(), "Subscribe request timed out with no response after "
                    + SUBSCRIBE_TIMEOUT_MS + "ms.");
                recordConnectFailure(credential.getId());
                existing.listener().state = ConnectionState.CLOSED;
                closeConnection(credential.getId(), "subscribe request timed out with no response");
                existing = null;
                expiringSoon = false;
            }

            if (!eligible && existing != null) {
                closeConnection(credential.getId(), "no longer eligible (auto-trade off or halted)");
            } else if (eligible && (existing == null || expiringSoon)) {
                // The backoff applies specifically to the "needs a new connection" case --
                // expiringSoon's own proactive refresh isn't a failure recovery, so it's never
                // subject to this skip.
                Instant skipUntil = existing == null ? nextRetryAllowedAt.get(credential.getId()) : null;
                if (skipUntil != null && Instant.now().isBefore(skipUntil)) {
                    continue;
                }
                if (expiringSoon) closeConnection(credential.getId(), "proactive refresh before 24h connection limit");
                connect(credential);
            }
        }
    }

    /**
     * Periodically asks Binance's own side whether it agrees a subscription is genuinely active,
     * rather than only trusting this class's own SUBSCRIBED state or the raw socket still being
     * open. Deliberately observability-only -- logs a mismatch, never forces a reconnect on its
     * own, for the residual-uncertainty reasons Listener.pendingSessionStatusId's own comment
     * explains. A slower cadence than reconcileConnections' own 30s -- this is a lower-priority,
     * best-effort cross-check, not a safety-critical gate, and every session.status call still
     * consumes real request weight against Binance's own rate limits.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000, scheduler = "scanScheduler")
    public void verifySubscriptionHealth() {
        if (shutdownState.isShuttingDown()) return;
        for (var entry : connections.entrySet()) {
            String credentialId = entry.getKey();
            ManagedConnection conn = entry.getValue();
            if (conn.listener().state != ConnectionState.SUBSCRIBED) continue; // nothing meaningful to verify yet

            // The previous probe sent from this same method, one cycle ago, never got any
            // response at all (onText's own match above would have cleared this otherwise). See
            // MAX_CONSECUTIVE_MISSED_SESSION_STATUS's own javadoc.
            if (conn.listener().pendingSessionStatusId != null) {
                int missed = ++conn.listener().consecutiveMissedSessionStatus;
                if (missed >= MAX_CONSECUTIVE_MISSED_SESSION_STATUS) {
                    log.warn("User-data-stream for credential {} has not answered {} consecutive session.status health probes "
                            + "({} minutes of total silence to an active probe, not merely a quiet account) -- treating this as a "
                            + "hung connection onClose/onError will never fire for on its own. Force-closing so the next "
                            + "reconcileConnections sweep reopens it.",
                        credentialId, missed, (missed * 300_000L) / 60_000L);
                    exchangeHealthService.recordWsError(credentialId,
                        missed + " consecutive session.status health probes went unanswered -- connection force-closed as hung.");
                    recordConnectFailure(credentialId);
                    conn.listener().state = ConnectionState.CLOSED;
                    closeConnection(credentialId, "hung connection -- " + missed + " consecutive session.status probes unanswered");
                    continue; // don't send a new probe to a connection we just closed
                }
            }

            String requestId = UUID.randomUUID().toString();
            conn.listener().pendingSessionStatusId = requestId;
            String message = "{\"id\":\"" + requestId + "\",\"method\":\"session.status\"}";
            try {
                conn.socket().sendText(message, true);
            } catch (Exception e) {
                log.warn("Could not send session.status request for credential {}: {}", credentialId, e.getMessage());
                conn.listener().pendingSessionStatusId = null;
            }
        }
    }

    /**
     * sendClose() returns a CompletableFuture (the actual close handshake is asynchronous), so
     * @PreDestroy must not return immediately while a close handshake is still in flight -- a
     * fast JVM exit or HTTP client shutdown right after could abandon it mid-handshake. Blocked
     * on each with a short, bounded timeout so this method only returns once every connection has
     * actually finished closing (or timed out trying) — a real wait, not a fire-and-forget call
     * that merely looks synchronous.
     */
    @PreDestroy
    public void closeAllStreams() {
        if (connections.isEmpty()) return;
        log.warn("Shutdown: closing {} active Binance user-data-stream connection(s).", connections.size());
        List<CompletableFuture<WebSocket>> closeFutures = new java.util.ArrayList<>();
        for (String credentialId : java.util.List.copyOf(connections.keySet())) {
            var future = closeConnection(credentialId, "application shutdown");
            if (future != null) closeFutures.add(future);
        }
        try {
            CompletableFuture.allOf(closeFutures.toArray(new CompletableFuture[0]))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            // A slow or unresponsive close handshake must never block the JVM from actually
            // shutting down -- logged, not rethrown. Binance's own server-side timeout closes
            // any connection this bounded wait couldn't confirm, same outcome either way.
            log.warn("Not every user-data-stream connection confirmed closed within the shutdown timeout: {}", e.getMessage());
        }
    }

    /**
     * Applies a pushed executionReport directly to the local OMS Order record.
     *
     * HONEST SCOPE, stated plainly: this updates only the local OMS Order record's own status/
     * filledQuantity/averageFillPrice, directly, the moment a real fill or terminal status is
     * pushed. It does not touch the Fill Ledger or Position directly -- those remain entirely
     * the responsibility of the existing REST reconciliation trigger immediately below in
     * onText(). A genuinely complete "executionReport -> OMS -> Fill Ledger -> Position" chain,
     * with the WebSocket driving every link rather than just the first, would mean re-deriving
     * PositionMonitorService's own reconciliation logic to work directly off pushed WS data
     * instead of REST-fetched fills -- a substantially larger, higher-risk rewrite of this
     * application's actual trust model for money-moving state, not attempted here. This closes
     * the specific, narrower gap of needing another REST round trip before internal state
     * changes during a volatile event for the OMS Order record specifically, while leaving Fill
     * Ledger/Position exactly as safe (and exactly as REST-dependent) as they already were.
     */
    private void applyExecutionReportFastPath(JsonNode event) {
        String clientOrderId = event.path("c").asText(null);
        if (clientOrderId == null) return;
        var orderOpt = orderRepo.findByClientOrderId(clientOrderId);
        if (orderOpt.isEmpty()) return; // not an order this application's own OMS is tracking
        com.tradevision.model.Order order = orderOpt.get();
        // Already resolved -- this fast path has nothing further to do for it, and attempting
        // an update here would only hit OrderService's own, already-hardened illegal-transition
        // guard (caught below regardless, but there's no reason to attempt it at all).
        if (java.util.EnumSet.of(com.tradevision.model.OrderStatus.FILLED, com.tradevision.model.OrderStatus.CANCELLED,
                com.tradevision.model.OrderStatus.EXPIRED, com.tradevision.model.OrderStatus.REJECTED,
                com.tradevision.model.OrderStatus.RECONCILIATION_REQUIRED).contains(order.getStatus())) {
            return;
        }

        String executionType = event.path("x").asText(null);
        String orderStatus = event.path("X").asText(null);
        String brokerOrderId = event.path("i").isMissingNode() ? null : event.path("i").asText(null);
        try {
            if ("CANCELED".equals(orderStatus)) {
                orderService.markCancelled(order);
            } else if ("REJECTED".equals(orderStatus)) {
                // A genuine REJECTED can never carry a prior fill (the exchange refused the
                // order before any matching could happen at all) -- success=false here is
                // correct and loses nothing, matching OrderService.recordBrokerResult's own
                // "the broker call itself failed" interpretation of success=false.
                var result = new com.tradevision.service.broker.dto.OrderResult(false, brokerOrderId, clientOrderId, orderStatus,
                    null, null, event.toString(), "Binance executionReport reports orderStatus=" + orderStatus);
                orderService.recordBrokerResult(order, result);
            } else if ("EXPIRED".equals(orderStatus) || "EXPIRED_IN_MATCH".equals(orderStatus)) {
                // EXPIRED must not go through the same success=false/executedQty=null branch as
                // REJECTED above, which OrderService.recordBrokerResult treats as "the broker
                // call itself failed" and maps unconditionally to OrderStatus.REJECTED --
                // discarding any quantity that genuinely did fill before this order expired
                // (e.g. IOC/FOK/self-trade-prevention: a resting order fills 0.4 of 1.0, then
                // the unfilled 0.6 remainder expires). Unlike REJECTED, EXPIRED is a real,
                // definitive terminal status from the exchange, not a failed API call -- the
                // same z/Z cumulative-fill fields the TRADE branch below already reads apply
                // here too (Binance populates them on every executionReport, including the
                // final EXPIRED one), so this reads them the same way and reports success=true
                // with the real executed quantity/price, letting
                // OrderService.recordBrokerResult's own dedicated EXPIRED/EXPIRED_IN_MATCH
                // handling record the correct terminal state with whatever quantity actually
                // filled, instead of discarding it as a rejection.
                java.math.BigDecimal cumQty = new java.math.BigDecimal(event.path("z").asText("0"));
                java.math.BigDecimal cumQuote = new java.math.BigDecimal(event.path("Z").asText("0"));
                java.math.BigDecimal avgPrice = cumQty.signum() > 0
                    ? cumQuote.divide(cumQty, 8, java.math.RoundingMode.HALF_UP) : java.math.BigDecimal.ZERO;
                var result = new com.tradevision.service.broker.dto.OrderResult(true, brokerOrderId, clientOrderId, orderStatus,
                    cumQty, avgPrice, event.toString(), null);
                orderService.recordBrokerResult(order, result);
            } else if ("TRADE".equals(executionType)) {
                // z = cumulative filled quantity, Z = cumulative quote asset transacted quantity
                // -- confirmed against Binance's own current documentation before writing this,
                // not assumed. Average price = Z / z, the same relationship Binance's own docs
                // state explicitly for this exact event.
                java.math.BigDecimal cumQty = new java.math.BigDecimal(event.path("z").asText("0"));
                java.math.BigDecimal cumQuote = new java.math.BigDecimal(event.path("Z").asText("0"));
                java.math.BigDecimal avgPrice = cumQty.signum() > 0
                    ? cumQuote.divide(cumQty, 8, java.math.RoundingMode.HALF_UP) : java.math.BigDecimal.ZERO;
                var result = new com.tradevision.service.broker.dto.OrderResult(true, brokerOrderId, clientOrderId, orderStatus,
                    cumQty, avgPrice, event.toString(), null);
                orderService.recordBrokerResult(order, result);
            }
            // Anything else (a plain "NEW" ack with no fill yet) is deliberately left to the
            // REST reconciliation trigger below -- this order was already ACKNOWLEDGED
            // synchronously by AutoTradeService's own REST response right after placement, so a
            // bare ack here carries no new information this fast path needs to act on.
        } catch (Exception e) {
            log.debug("OMS fast-path update from executionReport failed for order {} (clientOrderId {}) -- non-fatal, since REST "
                + "reconciliation below remains the actual safety net regardless: {}", order.getId(), clientOrderId, e.getMessage());
        }
    }

    private void connect(BrokerCredential credential) {
        if (credential.getBroker() != BrokerType.BINANCE) return; // only adapter with a stream implementation
        String apiKey = credentialService.decrypt(credential, true);
        String apiSecret = credentialService.decrypt(credential, false);
        String base = credential.getMode() == BrokerMode.LIVE ? LIVE_WS_API : TESTNET_WS_API;

        Listener listener = new Listener(credential.getId());
        CompletableFuture<WebSocket> future = httpClient.newWebSocketBuilder()
            // Binance's own docs state the API key must be in the X-MBX-APIKEY header to
            // authenticate the WS connection itself — separate from the apiKey field inside the
            // subscribe.signature message body.
            .header("X-MBX-APIKEY", apiKey)
            .buildAsync(URI.create(base), listener);

        future.thenAccept(socket -> {
            // connect() itself is only ever called after an eligibility check that already
            // includes !shutdownState.isShuttingDown(), but this callback runs asynchronously,
            // after the real network handshake completes -- shutdown can begin in that exact
            // window. Re-verify shutdown state at the one point that actually matters,
            // immediately before this connection would otherwise become live and start
            // receiving real account data.
            if (shutdownState.isShuttingDown()) {
                log.info("Shutdown began while connecting for credential {} -- closing this newly-established socket instead of "
                    + "registering it.", credential.getId());
                try {
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "Application shutting down.");
                } catch (Exception e) {
                    log.debug("Error closing a connection established during shutdown (non-fatal): {}", e.getMessage());
                }
                return;
            }
            connections.put(credential.getId(), new ManagedConnection(socket, Instant.now(), listener));
            subscribe(socket, listener, apiKey, apiSecret);
            log.info("User-data-stream raw connection opened for credential {} ({}) — awaiting subscribe confirmation", credential.getId(), credential.getMode());
        }).exceptionally(e -> {
            log.warn("Could not open user-data-stream for credential {}: {}", credential.getId(), e.getMessage());
            recordConnectFailure(credential.getId());
            return null;
        });
    }

    private void subscribe(WebSocket socket, Listener listener, String apiKey, String apiSecret) {
        long timestamp = System.currentTimeMillis();
        // Params sorted alphabetically per Binance's signing rule: apiKey, then timestamp.
        // Unlike order-placement query strings (BinanceBrokerAdapter.enc()), these two values are
        // guaranteed to be plain alphanumeric/digits — an API key and a millisecond timestamp
        // never contain characters percent-encoding would change — so no encoding step is needed
        // here for the signature to match what's actually sent.
        String payload = "apiKey=" + apiKey + "&timestamp=" + timestamp;
        String signature = hmacSha256(apiSecret, payload);

        String requestId = UUID.randomUUID().toString();
        // This exact id is what onText() watches for to recognize this specific request's own
        // response, not just any message. See Listener.pendingSubscribeId's own field comment.
        listener.pendingSubscribeId = requestId;
        listener.state = ConnectionState.SUBSCRIBING;
        listener.pendingSubscribeAt = Instant.now();
        String message = "{"
            + "\"id\":\"" + requestId + "\","
            + "\"method\":\"userDataStream.subscribe.signature\","
            + "\"params\":{"
                + "\"apiKey\":\"" + apiKey + "\","
                + "\"timestamp\":" + timestamp + ","
                + "\"signature\":\"" + signature + "\""
            + "}}";
        socket.sendText(message, true);
    }

    /**
     * Returns the close handshake's own future so callers that need to genuinely wait for
     * completion (closeAllStreams' own shutdown path) can -- callers that don't care (the
     * reconciliation sweep's own proactive/no-longer-eligible closes) simply ignore it, same as
     * before this method had a return value at all.
     */
    private CompletableFuture<WebSocket> closeConnection(String credentialId, String reason) {
        ManagedConnection existing = connections.remove(credentialId);
        if (existing != null) {
            try {
                return existing.socket().sendClose(WebSocket.NORMAL_CLOSURE, reason);
            } catch (Exception e) {
                log.warn("Error closing user-data-stream for credential {}: {}", credentialId, e.getMessage());
            }
        }
        return null;
    }

    /** Listener per connection. Deliberately thin — see the class javadoc on the "trigger, don't
     *  trust" design. */
    private class Listener implements WebSocket.Listener {
        private final String credentialId;
        private final StringBuilder buffer = new StringBuilder();
        // This codebase uses the newer WebSocket API (userDataStream.subscribe.signature, see
        // this class's own top-level javadoc for why), which sends an explicit subscribe request
        // after the raw connection opens and returns its own separate success/error response --
        // genuinely different from the deprecated raw-stream-URL approach, where the listenKey
        // in the URL path is the subscription and there's nothing further to confirm. onOpen()
        // only means the raw WebSocket handshake succeeded; the subscribe request can still fail
        // independently (bad signature, expired key, rate limit), so onText() must separately
        // watch for a response matching this request's own "id", not just any "event". Tracks
        // the pending subscribe request's id so onText() can recognize its response specifically.
        private volatile String pendingSubscribeId;
        /**
         * The timestamp reconcileConnections checks against SUBSCRIBE_TIMEOUT_MS to detect a
         * subscribe request Binance never responded to at all (not even an error) -- without
         * this, pendingSubscribeId would stay set forever with nothing to time it out.
         */
        private volatile Instant pendingSubscribeAt;
        /**
         * The connection's state machine (see ConnectionState's own class-level comment for its
         * full scope and why DEGRADED isn't modeled). Starts at RAW_CONNECTED the moment this
         * Listener is constructed, immediately before buildAsync() (see connect()'s own
         * ordering) -- never observable from outside this class until a ManagedConnection
         * referencing it is actually registered in `connections`, which only happens after the
         * raw handshake genuinely succeeds. Distinguishes "this connection exists in the map"
         * from "this connection is actually subscribed and receiving Binance's own confirmed
         * event stream." Exposed via getConnectionState()/isSubscribed() below.
         */
        private volatile ConnectionState state = ConnectionState.RAW_CONNECTED;
        /**
         * Binance's own executionReport/listStatus/outboundAccountPosition payloads all carry a
         * real "E" (event time, epoch millis) field, verified against Binance's own API
         * documentation. This is pure observability, not a gating decision -- this does not
         * reject, reorder, or buffer events based on this value; it just makes it possible to
         * see when delivery was out-of-order, via onText's own comparison against this field
         * below, while REST reconciliation remains the authoritative state.
         */
        private volatile long lastEventTimeMs = 0L;
        /**
         * Tracks the outstanding session.status request this class periodically sends, same
         * request/response-matching pattern as pendingSubscribeId above. Binance itself documents
         * session.status (and session.subscriptions) as the way to verify a subscription is
         * still genuinely active, separate from and in addition to this class's own SUBSCRIBED
         * state or the raw socket still being open -- neither of those proves Binance's own side
         * agrees the subscription is live. Deliberately observability-only, same reasoning as
         * lastEventTimeMs above -- the documentation for this flag's precise scope under
         * userDataStream.subscribe.signature specifically (the method this class actually uses)
         * is not fully unambiguous, so a false mismatch here must never trigger an automatic
         * reconnect on its own -- only a logged warning an operator can investigate, so an
         * incorrect assumption about this flag's own scope produces a misleading log line at
         * worst, never reconnect churn or a false sense of security that suppresses a real one.
         */
        private volatile String pendingSessionStatusId;
        /**
         * Counts consecutive verifySubscriptionHealth cycles where the previous session.status
         * probe never got any response at all (not a mismatch -- a genuine non-response). See
         * MAX_CONSECUTIVE_MISSED_SESSION_STATUS's own javadoc. Reset to 0 whenever a response of
         * any kind arrives (onText's own pendingSessionStatusId match below), so only sustained
         * silence to repeated active probes accumulates this, never a single slow round-trip.
         */
        private volatile int consecutiveMissedSessionStatus = 0;

        Listener(String credentialId) {
            this.credentialId = credentialId;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            webSocket.request(1);
            if (!last) return null;
            // Recorded as soon as a complete message arrives, before any parsing — a malformed
            // message this stream can't parse is still real evidence the connection is alive and
            // delivering data, which is exactly what this metric is meant to capture.
            exchangeHealthService.recordWsMessageReceived(credentialId);

            String message = buffer.toString();
            buffer.setLength(0);
            try {
                JsonNode root = mapper.readTree(message);

                // Recognize the subscribe request's own response by its matching "id" (Binance's
                // WebSocket API request/response convention, same "id" echoed back that this
                // class's own subscribe() method generates), before falling through to the
                // ordinary event-type handling below (a genuine data event has no "id" field at
                // all, so this check is naturally exclusive with it).
                String responseId = root.path("id").asText(null);
                if (responseId != null && responseId.equals(pendingSubscribeId)) {
                    if (root.has("error")) {
                        // Closes the socket and removes it from `connections` in the same call
                        // closeConnection already provides, so the very next reconciliation
                        // sweep sees existing == null and genuinely retries (subject to the
                        // backoff recordConnectFailure below still enforces), rather than leaving
                        // a zombie entry that reconcileConnections' own
                        // `existing != null && !expiringSoon` check would see and conclude there
                        // was nothing to do, potentially for hours.
                        log.warn("User-data-stream subscribe request FAILED for credential {}: {}", credentialId, root.path("error").toString());
                        exchangeHealthService.recordWsError(credentialId, "Subscribe request failed: " + root.path("error").toString());
                        recordConnectFailure(credentialId);
                        state = ConnectionState.CLOSED;
                        closeConnection(credentialId, "subscribe request failed -- " + root.path("error").toString());
                    } else {
                        // This -- not onOpen() -- is the actual, honest "connected" moment: the
                        // raw handshake succeeded AND Binance confirmed the subscription itself.
                        exchangeHealthService.recordWsConnected(credentialId);
                        recordConnectSuccess(credentialId);
                        state = ConnectionState.SUBSCRIBED;
                    }
                    pendingSubscribeId = null;
                    return null;
                }

                // Recognizes this class's own periodic session.status request by its matching
                // id, same pattern as the subscribe response above. See
                // Listener.pendingSessionStatusId's own field comment. Observability only -- a
                // mismatch is logged, never acted on automatically.
                if (responseId != null && responseId.equals(pendingSessionStatusId)) {
                    pendingSessionStatusId = null;
                    // A response of any kind (even an error response) proves this connection is
                    // still genuinely alive and answering probes -- resets the hung-connection
                    // counter, which only accumulates on total non-response.
                    consecutiveMissedSessionStatus = 0;
                    if (root.has("error")) {
                        log.warn("session.status request failed for credential {}: {}", credentialId, root.path("error").toString());
                    } else {
                        JsonNode userDataStreamFlag = root.path("result").path("userDataStream");
                        if (userDataStreamFlag.isBoolean() && !userDataStreamFlag.asBoolean()) {
                            log.warn("session.status for credential {} reports userDataStream=false even though this class believes "
                                + "it is SUBSCRIBED -- Binance's own side may no longer consider this subscription active. This is "
                                + "logged only, not acted on automatically (see Listener.pendingSessionStatusId's own field comment "
                                + "for why); the 60-second REST reconciliation remains the actual safety net regardless.", credentialId);
                        } else if (userDataStreamFlag.isNull() || userDataStreamFlag.isMissingNode()) {
                            log.debug("session.status for credential {} did not report a userDataStream flag at all -- inconclusive, "
                                + "not treated as a mismatch.", credentialId);
                        }
                    }
                    return null;
                }

                JsonNode event = root.path("event");
                String eventType = event.path("e").asText(null);
                // Logged, not acted on. See lastEventTimeMs's own field comment. A genuinely
                // missing "E" field (0, absent from the payload) is never treated as out-of-order
                // against a real prior value; the check only fires when both this event and the
                // prior one actually carried a real timestamp. lastEventTimeMs itself is
                // monotonic -- the freshest event time ever actually seen, never regressed
                // backward by a later-arriving-but-earlier-timestamped (out-of-order) message.
                long eventTimeMs = event.path("E").asLong(0L);
                if (eventTimeMs > 0 && lastEventTimeMs > 0 && eventTimeMs < lastEventTimeMs) {
                    log.warn("User-data-stream event {} for credential {} arrived OUT OF ORDER: its own event time {} is earlier than "
                        + "the last event's {} ({}ms behind). REST reconciliation remains authoritative regardless, but this is worth "
                        + "investigating if it recurs.", eventType, credentialId, eventTimeMs, lastEventTimeMs, lastEventTimeMs - eventTimeMs);
                }
                if (eventTimeMs > lastEventTimeMs) lastEventTimeMs = eventTimeMs;
                // executionReport = an order changed state (new/partial/filled/canceled/rejected).
                // listStatus = an OCO order-list changed state (this is how a TP/SL exit surfaces).
                // outboundAccountPosition = a balance changed — relevant to drawdown tracking.
                //
                // Scoped only to executionReport (the one event type carrying real order/fill
                // data this application's own OMS can act on directly) -- applies a real fill or
                // terminal-status update to the local Order record immediately via OrderService's
                // own already-hardened, atomic-conditional-update methods
                // (recordBrokerResult/markCancelled), the same methods the synchronous REST
                // response from adapter.placeOrder() already uses. This is provably safe against
                // a race with the REST reconciliation trigger immediately below (which still
                // fires, unconditionally): recordBrokerResult's own atomic update is WHERE
                // status=<the order's own expected prior status>, so whichever of the two paths
                // (this WS fast-path, or a REST-driven update somewhere downstream of
                // reconcileCredential) gets there first simply wins -- the other's own
                // conditional update matches nothing and safely no-ops rather than corrupting
                // anything. REST remains the actual safety net for everything this narrower fast
                // path does not cover (Fill Ledger and Position updates, still driven entirely by
                // the existing reconciliation call below -- see this method's own
                // applyExecutionReportFastPath for the exact, honestly narrow scope of what this
                // fast path alone updates).
                if ("executionReport".equals(eventType)) {
                    applyExecutionReportFastPath(event);
                }
                // Per the java.net.http.WebSocket.Listener contract, onText() is not permitted to
                // block: doing so stalls delivery of every subsequent message on this same socket
                // until the reconciliation call returns, and a burst of executionReport/listStatus
                // events (exactly what a multi-fill OCO exit, or several orders resolving within
                // the same second, produces) would queue up reconciliation calls back-to-back on
                // that one thread, each blocking the next. The reconciliation work is dispatched
                // off this thread entirely, onto scheduleDebouncedReconcile's own dedicated
                // wsReconcileDispatchScheduler pool, which also coalesces a burst of events for
                // the same credential into a single dispatch (see that method's own javadoc for
                // the full debounce design). onText() itself returns immediately after
                // scheduling, never blocking on reconcileCredential().
                if ("executionReport".equals(eventType) || "listStatus".equals(eventType) || "outboundAccountPosition".equals(eventType)) {
                    scheduleDebouncedReconcile(credentialId, eventType);
                }
            } catch (Exception e) {
                log.warn("Could not parse user-data-stream message for credential {}: {}", credentialId, e.getMessage());
            }
            return null;
        }

        /**
         * Dispatches a REST reconciliation for this credential onto wsReconcileDispatchScheduler
         * after a short debounce delay, instead of calling
         * positionMonitorService.reconcileCredential() synchronously on the socket's own receive
         * thread (see onText's own comment above for why that must never block).
         *
         * Coalescing: pendingWsReconciles.add(credentialId) is the guard -- it returns true only
         * for the first event of a burst for this credential; every subsequent event arriving
         * while that first dispatch is still pending (scheduled but not yet started) finds the id
         * already present and returns immediately, doing nothing further. A burst of N events
         * (Binance can emit several executionReport messages within milliseconds of each other
         * for a single multi-fill order, or an OCO's two legs resolving close together) therefore
         * collapses into exactly one scheduled reconciliation, not N.
         *
         * The id is removed from pendingWsReconciles at the start of the scheduled task, not the
         * end (see the task body below) -- deliberately, so an event that arrives while a
         * reconciliation is already running is not silently dropped. Once the running task clears
         * its own id, a new event can immediately schedule a fresh follow-up dispatch, ensuring
         * this application never permanently stops re-reconciling a credential just because its
         * events keep arriving faster than reconciliation can run. The tradeoff this accepts: in
         * the worst case (events arriving continuously, faster than reconciliation completes),
         * reconciliation runs back-to-back rather than exactly once per burst -- an acceptable
         * outcome given REST reconciliation itself remains idempotent/safe to run redundantly
         * (the entire rest of this codebase already depends on that property).
         *
         * RECONCILE_DEBOUNCE_MS (500ms) is the delay before the scheduled task actually runs --
         * long enough to let a genuine millisecond-scale burst finish arriving and be coalesced
         * into the single pending dispatch, short enough that this remains a genuine latency
         * improvement over calling reconcileCredential() synchronously for the common case of a
         * single, isolated event.
         *
         * Persisting raw events for replay is deliberately not attempted here: this codebase has
         * no durable event-log/outbox infrastructure of any kind today (no event-sourcing table,
         * no append-only log), and REST reconciliation already is this application's full replay
         * mechanism -- every reconcile call re-derives ground truth directly from Binance's own
         * REST API, not from locally cached/persisted event history, so a missed or dropped WS
         * event is never actually fatal (the periodic 60s reconciliationScheduler sweep is the
         * backstop of last resort regardless of what WS delivers or drops). Building genuine
         * event persistence and replay is a materially larger, separate undertaking than the
         * blocking/debounce handling this class ships.
         */
        private void scheduleDebouncedReconcile(String credentialId, String eventType) {
            if (!pendingWsReconciles.add(credentialId)) {
                // Already scheduled (or a dispatch is about to start) for this credential --
                // this event rides along with that pending/imminent reconciliation.
                return;
            }
            try {
                wsReconcileDispatchScheduler.schedule(() -> {
                    // Cleared at the start of the task, not the end -- see this method's own
                    // javadoc above for why: an event arriving during the reconciliation call
                    // below must be able to schedule its own fresh follow-up dispatch rather than
                    // being silently absorbed into a reconciliation that may already be stale by
                    // the time it returns.
                    pendingWsReconciles.remove(credentialId);
                    credentialRepo.findById(credentialId).ifPresent(credential -> {
                        log.debug("Debounced user-data-stream reconciliation for credential {} (triggered by {}).", credentialId, eventType);
                        try {
                            positionMonitorService.reconcileCredential(credential);
                        } catch (Exception e) {
                            log.warn("Debounced reconciliation from stream event failed for credential {}: {}", credentialId, e.getMessage());
                        }
                    });
                }, java.time.Instant.now().plusMillis(RECONCILE_DEBOUNCE_MS));
            } catch (Exception e) {
                // Scheduling itself failed (e.g. pool rejection) -- clear the pending flag so this
                // credential is never permanently stuck believing a dispatch is in flight when none
                // actually is; the next event for it will simply try to schedule again.
                pendingWsReconciles.remove(credentialId);
                log.warn("Could not schedule debounced reconciliation for credential {}: {}", credentialId, e.getMessage());
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            // recordWsConnected() is deliberately not called here -- this only means the raw
            // handshake succeeded, not that Binance has actually confirmed the subscription.
            // That happens in onText() instead, when the matching subscribe response arrives.
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("User-data-stream closed for credential {}: {} {}", credentialId, statusCode, reason);
            state = ConnectionState.CLOSED;
            // Removal is conditional on socket identity -- this callback's own webSocket must be
            // the currently registered one for this credential, or nothing is removed at all.
            // Without this check, a stale callback for an already-superseded socket could delete
            // a newer, live entry: the 23-hour proactive refresh closes socket A and opens
            // socket B for the same credential; if A's own onClose() callback fires after B is
            // already registered, an unconditional remove would delete B's own live, subscribed
            // entry -- the application would then believe there's no connection at all, or
            // reconciliation could open a third, duplicate connection. A stale callback for an
            // already-superseded socket is a genuine no-op here instead.
            connections.computeIfPresent(credentialId, (id, current) -> current.socket() == webSocket ? null : current);
            exchangeHealthService.recordWsDisconnected(credentialId, statusCode + " " + reason);
            recordConnectFailure(credentialId);
            // The reconcileConnections() sweep (30s, with the exponential backoff above layered
            // on top for a repeatedly-failing credential specifically) will reopen it if the
            // credential is still eligible — no independent reconnect-with-backoff loop here, to
            // avoid two different reconnection mechanisms racing each other. The backoff itself
            // lives in the sweep's own skip-this-credential check, not a second timer.
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("User-data-stream error for credential {}: {}", credentialId, error.getMessage());
            state = ConnectionState.CLOSED;
            // Same socket-identity guard as onClose's own comment above -- the same race can
            // happen here too.
            connections.computeIfPresent(credentialId, (id, current) -> current.socket() == webSocket ? null : current);
            exchangeHealthService.recordWsError(credentialId, error.getMessage());
            recordConnectFailure(credentialId);
        }
    }

    private String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign user-data-stream subscription", e);
        }
    }
}
