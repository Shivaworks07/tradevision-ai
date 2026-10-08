package com.tradevision.service.broker;

import com.tradevision.model.BrokerMode;
import com.tradevision.model.BrokerType;
import com.tradevision.model.PaperAccountBalance;
import com.tradevision.model.PaperOco;
import com.tradevision.repository.PaperAccountBalanceRepository;
import com.tradevision.repository.PaperOcoRepository;
import com.tradevision.service.broker.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A fully-isolated PAPER trading mode that never touches a real exchange account, as distinct
 * from TESTNET (which is still a real, authenticated connection to Binance's own testnet and
 * carries whatever behavior differences that implies). PAPER simulates trading entirely
 * in-process so a misconfigured credential or a testnet-specific quirk can never move real
 * money or produce false confidence.
 *
 * Design:
 * - This class is DELIBERATELY NOT a Spring-managed bean (no @Component/@Service). It is never
 *   auto-discovered into the List<BrokerAdapter> BrokerCredentialService already autowires by
 *   BrokerType -- doing so would have thrown a duplicate-key exception the instant this class's
 *   own getType() (necessarily BINANCE, the only real BrokerType this app has) collided with the
 *   real BinanceBrokerAdapter's own getType() in that map's construction. Instead,
 *   BrokerCredentialService constructs exactly one instance of this class manually, keyed off
 *   BrokerMode.PAPER specifically rather than BrokerType at all -- see its own updated
 *   adapterForCredential javadoc.
 * - Every TRADING-ACTION method (placeOrder, placeExitOco, cancelOrder, cancelOco, getOcoStatus,
 *   getOrderStatus, getFillsForOrder, getOpenOrders, getAccountPermissions, getBalance,
 *   createListenKey/keepAliveListenKey/closeListenKey) is fully simulated in this class,
 *   in-process -- NEVER an authenticated call to Binance, regardless of what apiKey/apiSecret a
 *   PAPER credential happens to have stored (a PAPER credential does not need real keys at all;
 *   any placeholder value works, since these methods never read apiKey/apiSecret's own values).
 * - Every pure MARKET-DATA read (getSymbolRules, getCurrentPrice, getSpread, getClockDriftMs,
 *   getOrderBookDepth, getRecentCandles, getAllTradableUsdtSymbols, getAll24hrTickers,
 *   getHistoricalPrice) delegates to the REAL BinanceBrokerAdapter, always with BrokerMode.LIVE
 *   -- these are Binance's own public, unauthenticated endpoints (confirmed against this
 *   codebase's own existing BinanceBrokerAdapter method signatures, several of which already
 *   take no apiKey/apiSecret parameter at all), so paper-trading fills are simulated against
 *   genuinely real, current market prices rather than fabricated or testnet-thin ones -- exactly
 *   what makes paper trading a meaningful rehearsal of real strategy behavior.
 * - Market orders fill immediately and completely at the real current price fetched at
 *   placement time -- a deliberate simplification (no slippage/partial-fill modeling), stated
 *   here rather than left implicit. OCOs are tracked in PaperOco and resolved lazily: every
 *   getOcoStatus() call fetches the real current price and checks whether it has crossed either
 *   trigger since the last check -- this means resolution happens on whatever cadence
 *   PositionMonitorService's own existing reconciliation polling already runs at, with no new
 *   background thread or scheduler needed.
 */
public class PaperBrokerAdapter implements BrokerAdapter {

    private static final Logger log = LoggerFactory.getLogger(PaperBrokerAdapter.class);

    private final BrokerAdapter realAdapterForMarketData;
    private final PaperOcoRepository paperOcoRepo;
    /**
     * Backs durable persistence of the simulated balance below, so a restart or redeploy resumes
     * a PAPER credential's running balance instead of silently resetting it back to the starting
     * default -- see PaperAccountBalance's own class javadoc. May be null for the one instance
     * BrokerCredentialService keeps purely for connect-time validation (doConnect has no
     * credentialId yet at that point -- the credential hasn't been saved), which never processes
     * a real simulated fill anyway, so that instance's own balance is never meaningfully read or
     * persisted either way.
     */
    private final PaperAccountBalanceRepository paperAccountBalanceRepo;
    /**
     * The PAPER credential this specific adapter instance's balance belongs to --
     * BrokerCredentialService's own paperBrokerAdaptersByCredential map gives each PAPER
     * credential its own adapter instance, so this is that same key, threaded through so this
     * instance can load and persist its own durable balance document under it. Null only for the
     * connect-validation-only instance described above.
     */
    private final String credentialId;
    /**
     * A real, internally-tracked running USDT balance, updated by every simulated fill's actual
     * cash flow and durably persisted per PAPER credential rather than reset on every restart.
     * BrokerAdapter.getBalance(apiKey, apiSecret, mode) has no credentialId parameter -- apiKey/
     * apiSecret are placeholder values for PAPER credentials, never read for identification --
     * so this per-instance field, rather than widening the shared interface, is what lets each
     * PAPER credential have its own tracked balance without touching every other implementation
     * of that interface. Only a genuinely new PAPER credential (or the connect-validation-only
     * instance, which never persists) starts at the disclosed 100,000 default.
     */
    private final java.util.concurrent.atomic.AtomicReference<BigDecimal> simulatedUsdtBalance;
    /**
     * The per-base-asset counterpart to simulatedUsdtBalance above, keyed by uppercase base asset
     * symbol (e.g. "BTC"). A BUY increases the relevant entry; a SELL is rejected outright (same
     * "refuse a fill a real exchange would reject" posture as the USDT insufficient-balance
     * check) unless this credential's tracked holdings genuinely cover the requested quantity,
     * and decreases that entry by exactly what filled.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicReference<BigDecimal>> simulatedAssetBalances;
    /**
     * Records each simulated order's real outcome (FILLED, PARTIALLY_FILLED, etc.), keyed by
     * both ids a caller might look it up by, so a later status check (e.g. during
     * reconciliation) reads back the actual simulated result rather than a hardcoded "FILLED"
     * literal that would silently upgrade a partial fill.
     */
    private final java.util.Map<String, OrderStatusInfo> simulatedOrderStatusByBrokerOrderId = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, OrderStatusInfo> simulatedOrderStatusByClientOrderId = new java.util.concurrent.ConcurrentHashMap<>();
    // Binance's own standard spot taker fee (0.1%) -- applied to every simulated fill's notional
    // value, same as a real market order would actually incur.
    private static final BigDecimal SIMULATED_TAKER_FEE_RATE = BigDecimal.valueOf(0.001);
    /**
     * A small, fixed slippage applied against the requester -- a BUY fills slightly above the
     * observed price, a SELL fills slightly below it, matching the direction real slippage moves
     * against a market order's own requester. 5 basis points (0.05%) is a reasonable,
     * conservative approximation for a liquid USDT pair, not a claim of matching any specific
     * real order book's actual depth, which this simulation has no access to model.
     */
    private static final BigDecimal SIMULATED_SLIPPAGE_RATE = BigDecimal.valueOf(0.0005);

    /**
     * Above this notional, a simulated order has a real, though modest, chance of not filling
     * completely -- the same market-impact intuition a real order book would produce, scaled by
     * order size rather than applied uniformly. Safe to simulate this way since
     * OrderService.recordBrokerResult and everything downstream already correctly handles a
     * PARTIALLY_FILLED broker result, the exact status the real BinanceBrokerAdapter already
     * produces for real partial fills.
     */
    private static final BigDecimal PARTIAL_FILL_NOTIONAL_THRESHOLD = BigDecimal.valueOf(5000);
    /**
     * Matches BinanceBrokerAdapter.placeExitOco's own real STOP_LOSS_LIMIT buffer exactly
     * (belowPrice = stopTrigger * 0.995, a 0.5% resting-limit gap below the trigger) -- the
     * real-world distance a fast price move has to cross past the trigger before the resting
     * limit leg is left behind, unfilled. Reused here as the threshold for when a simulated SL
     * crossing is left SL_TRIGGERED_UNFILLED instead of auto-resolving to SL_FILLED, so the
     * simulated gap matches the real gap it's modeling, not an arbitrary one.
     */
    private static final BigDecimal STOP_LIMIT_NON_FILL_GAP_RATE = BigDecimal.valueOf(0.005);
    /**
     * A real, injectable random source -- deliberately java.util.Random, not ThreadLocalRandom,
     * since ThreadLocalRandom cannot be seeded or replaced, which would make the partial-fill
     * simulation above untestable and could make a test asserting an exact executedQty flaky
     * whenever an order's notional crosses PARTIAL_FILL_NOTIONAL_THRESHOLD. Defaults to genuine
     * randomness in production; setRandomForTesting below lets tests force deterministic
     * behavior instead of relying on statistical luck.
     */
    private java.util.Random random = new java.util.Random();

    /** Package-private, test-only -- see the random field's own javadoc for why this exists. */
    void setRandomForTesting(java.util.Random random) {
        this.random = random;
    }

    /**
     * The connect-validation-only construction path (BrokerCredentialService.doConnect) and any
     * test that has no PAPER credential yet -- balance is in-memory-only for the lifetime of
     * this instance, with no durable persistence.
     */
    public PaperBrokerAdapter(BrokerAdapter realAdapterForMarketData, PaperOcoRepository paperOcoRepo) {
        this(realAdapterForMarketData, paperOcoRepo, null, null);
    }

    /**
     * The real construction path: BrokerCredentialService.adapterForCredential passes a real
     * repo and this PAPER credential's own id, so this instance loads whatever durable balance
     * already exists for it (a restart/redeploy resumes a real paper-trading track record
     * instead of silently resetting it), or starts at the disclosed 100,000 default and persists
     * that starting point immediately for a genuinely new credential.
     */
    public PaperBrokerAdapter(BrokerAdapter realAdapterForMarketData, PaperOcoRepository paperOcoRepo,
                               PaperAccountBalanceRepository paperAccountBalanceRepo, String credentialId) {
        this.realAdapterForMarketData = realAdapterForMarketData;
        this.paperOcoRepo = paperOcoRepo;
        this.paperAccountBalanceRepo = paperAccountBalanceRepo;
        this.credentialId = credentialId;
        BigDecimal initialBalance = BigDecimal.valueOf(100_000);
        java.util.Map<String, BigDecimal> initialAssetBalances = new java.util.HashMap<>();
        if (paperAccountBalanceRepo != null && credentialId != null) {
            var existing = paperAccountBalanceRepo.findById(credentialId);
            if (existing.isPresent()) {
                initialBalance = existing.get().getBalanceUsdt();
                // Asset holdings are resumed too, same reasoning as the USDT balance above -- a
                // legacy record with no assetBalances field at all defaults to an empty map on
                // deserialization, the correct starting point for a credential that has never
                // had holdings tracked.
                if (existing.get().getAssetBalances() != null) initialAssetBalances.putAll(existing.get().getAssetBalances());
                log.info("[PAPER] Resumed durable balance {} USDT and {} asset holding(s) for credential {}",
                    initialBalance, initialAssetBalances.size(), credentialId);
            } else {
                persistBalances(initialBalance, initialAssetBalances);
            }
        }
        this.simulatedUsdtBalance = new java.util.concurrent.atomic.AtomicReference<>(initialBalance);
        this.simulatedAssetBalances = new java.util.concurrent.ConcurrentHashMap<>();
        initialAssetBalances.forEach((asset, qty) ->
            simulatedAssetBalances.put(asset, new java.util.concurrent.atomic.AtomicReference<>(qty)));
    }

    /**
     * Upserts this credential's durable balance document, USDT cash and per-asset holdings
     * together. No-op for the connect-validation-only instance (paperAccountBalanceRepo/
     * credentialId null).
     */
    private void persistBalances(BigDecimal usdtBalance, java.util.Map<String, BigDecimal> assetBalances) {
        if (paperAccountBalanceRepo == null || credentialId == null) return;
        var record = new PaperAccountBalance();
        record.setCredentialId(credentialId);
        record.setBalanceUsdt(usdtBalance);
        record.setAssetBalances(new java.util.HashMap<>(assetBalances));
        record.setUpdatedAt(java.time.LocalDateTime.now());
        paperAccountBalanceRepo.save(record);
    }

    /** Snapshot of every currently-tracked asset balance, for persistBalances to save alongside a USDT update. */
    private java.util.Map<String, BigDecimal> snapshotAssetBalances() {
        java.util.Map<String, BigDecimal> snapshot = new java.util.HashMap<>();
        simulatedAssetBalances.forEach((asset, ref) -> snapshot.put(asset, ref.get()));
        return snapshot;
    }

    @Override
    public BrokerType getType() {
        // Never actually looked up via this value -- BrokerCredentialService routes to this
        // instance by BrokerMode.PAPER directly, not through the BrokerType-keyed map. See this
        // class's own javadoc for why it can never be registered in that map at all.
        return BrokerType.BINANCE;
    }

    @Override
    public AccountPermissions getAccountPermissions(String apiKey, String apiSecret, BrokerMode mode) {
        // Simulated, unconditionally safe: a PAPER credential can always trade and can never withdraw.
        return new AccountPermissions(true, false, true);
    }

    // A PAPER credential is never actually tied to a real Binance account -- any placeholder
    // apiKey/apiSecret works. A single constant identity means rotation's own account-identity
    // check (which only ever runs for real accounts) is never spuriously triggered for PAPER,
    // without pretending this simulates a real per-account identity it has no way to verify.
    @Override
    public String getAccountUid(String apiKey, String apiSecret, BrokerMode mode) {
        return "PAPER";
    }

    // A PAPER credential has no real key restrictions to report -- simulated, unconditionally
    // safe, mirroring getAccountPermissions' own simulated-safe values just above.
    @Override
    public ApiKeyRestrictions getApiKeyRestrictions(String apiKey, String apiSecret, BrokerMode mode) {
        return new ApiKeyRestrictions(true, false, false, false, true);
    }

    @Override
    public List<AssetBalance> getBalance(String apiKey, String apiSecret, BrokerMode mode) {
        // Genuinely tracked per PAPER credential (BrokerCredentialService gives each PAPER
        // credential its own adapter instance), updated by every simulated fill's real cash flow
        // -- see placeOrder below -- and durably persisted across restarts.
        return List.of(new AssetBalance("USDT", simulatedUsdtBalance.get(), BigDecimal.ZERO));
    }

    @Override
    public OrderResult placeOrder(String apiKey, String apiSecret, BrokerMode mode, OrderRequest request) {
        BigDecimal observedPrice = realAdapterForMarketData.getCurrentPrice(request.symbol(), BrokerMode.LIVE);
        if (observedPrice == null) {
            return new OrderResult(false, null, request.clientOrderId(), "REJECTED", null, null, "{}",
                "Paper trading could not fetch a real current price for " + request.symbol() + " to simulate this fill.");
        }
        // Rounds the requested quantity to the symbol's real stepSize, the same way
        // BinanceBrokerAdapter.doPlaceOrder always does before placing a real order -- without
        // this, a simulated fill could report an executedQty a real Binance order for the same
        // symbol could never actually produce, undermining paper trading's purpose as a
        // meaningful rehearsal of real strategy behavior. Reuses getSymbolRules below (already
        // delegated to the real adapter) rather than duplicating rounding logic. Fails open
        // (falls back to the unrounded quantity) if the rules lookup itself fails, so a
        // market-data lookup failure never blocks an otherwise-good simulated fill.
        BigDecimal requestedQty = request.quantity();
        // Captured here, alongside the step-size lookup that already fetches the same
        // SymbolRules, rather than a second lookup. Left null (same fail-open posture as the
        // step-size rounding immediately below) if the lookup fails -- the oversell check
        // further down simply cannot be enforced for this one fill in that case.
        String baseAsset = null;
        try {
            SymbolRules rules = getSymbolRules(request.symbol(), mode);
            baseAsset = rules.baseAsset();
            if (rules.stepSize() != null && rules.stepSize().signum() > 0) {
                BigDecimal steps = requestedQty.divide(rules.stepSize(), 0, java.math.RoundingMode.DOWN);
                BigDecimal rounded = steps.multiply(rules.stepSize());
                if (rounded.signum() > 0) requestedQty = rounded;
            }
        } catch (Exception e) {
            log.debug("[PAPER] Could not load symbol rules for {} to round the simulated quantity to step size (non-fatal, filling the unrounded quantity): {}",
                request.symbol(), e.getMessage());
        }
        // Slippage moves against the requester's own direction -- a BUY fills slightly higher
        // than observed, a SELL fills slightly lower, matching the actual direction real
        // market-order slippage moves.
        boolean isBuy = "BUY".equalsIgnoreCase(request.side());
        BigDecimal slippageMultiplier = isBuy
            ? BigDecimal.ONE.add(SIMULATED_SLIPPAGE_RATE)
            : BigDecimal.ONE.subtract(SIMULATED_SLIPPAGE_RATE);
        BigDecimal fillPrice = observedPrice.multiply(slippageMultiplier).setScale(8, java.math.RoundingMode.HALF_UP);
        // A BUY must not be simulated as filling in full regardless of the tracked balance, or
        // simulatedUsdtBalance could go arbitrarily negative -- something a real exchange would
        // reject outright with an insufficient-balance error. Checked against the full requested
        // notional (before any partial-fill roll below, which can only ever reduce the actual
        // cash needed) so this can never itself be the reason a fill later turns out to have
        // spent more than was actually available.
        if (isBuy) {
            BigDecimal fullNotional = requestedQty.multiply(fillPrice);
            BigDecimal fullFee = fullNotional.multiply(SIMULATED_TAKER_FEE_RATE);
            if (simulatedUsdtBalance.get().compareTo(fullNotional.add(fullFee)) < 0) {
                return new OrderResult(false, null, request.clientOrderId(), "REJECTED", null, null, "{}",
                    "Paper trading balance " + simulatedUsdtBalance.get() + " USDT is insufficient for this order's simulated cost "
                        + fullNotional.add(fullFee) + " USDT -- refusing to simulate a fill a real exchange would reject.");
            }
        } else if (baseAsset != null) {
            // A SELL must not be simulated as filling regardless of whether this PAPER
            // credential has ever actually bought any of this asset. Checked against the full
            // requested quantity (before any partial-fill roll below, which can only ever reduce
            // the actual quantity needed), same reasoning as the BUY-side USDT check above.
            BigDecimal held = simulatedAssetBalances.getOrDefault(baseAsset, new java.util.concurrent.atomic.AtomicReference<>(BigDecimal.ZERO)).get();
            if (held.compareTo(requestedQty) < 0) {
                return new OrderResult(false, null, request.clientOrderId(), "REJECTED", null, null, "{}",
                    "Paper trading holdings of " + held + " " + baseAsset + " are insufficient to sell " + requestedQty
                        + " -- refusing to simulate a fill a real exchange would reject as an oversell.");
            }
        }
        String brokerOrderId = "PAPER-" + UUID.randomUUID();
        // Only orders above the notional threshold are eligible for a partial fill -- small
        // orders always fill completely, matching real liquidity intuition (a $50 market order
        // essentially never partial-fills; a $50,000 one might).
        // Eligible orders get roughly a 1-in-10 chance of a partial fill, landing somewhere
        // between 70% and 99% of the requested quantity when it happens.
        BigDecimal notional = requestedQty.multiply(fillPrice);
        BigDecimal executedQty = requestedQty;
        String resultStatus = "FILLED";
        if (notional.compareTo(PARTIAL_FILL_NOTIONAL_THRESHOLD) > 0 && random.nextInt(10) == 0) {
            double fillFraction = 0.70 + random.nextDouble() * 0.29; // [0.70, 0.99)
            executedQty = requestedQty.multiply(BigDecimal.valueOf(fillFraction)).setScale(8, java.math.RoundingMode.DOWN);
            resultStatus = "PARTIALLY_FILLED";
            notional = executedQty.multiply(fillPrice); // recompute notional against what actually filled, for the cash-flow update below
        }
        // A BUY spends USDT (notional + fee), a SELL receives USDT (notional - fee) -- the same
        // net cash-flow direction a real spot fill produces, correct regardless of whether this
        // specific order is an entry or an exit from this adapter's own point of view (it has no
        // opinion on that; Position/Order-level entry/exit semantics belong to the caller, not
        // this method). Uses executedQty, not the originally requested quantity, so a partial
        // fill's own cash flow is honest too.
        BigDecimal fee = notional.multiply(SIMULATED_TAKER_FEE_RATE);
        BigDecimal cashFlow = isBuy ? notional.add(fee).negate() : notional.subtract(fee);
        BigDecimal newBalance = simulatedUsdtBalance.updateAndGet(bal -> bal.add(cashFlow));
        // Per-asset holdings update, mirroring the USDT cash-flow update immediately above -- a
        // BUY increases holdings by what actually filled, a SELL decreases them, using
        // executedQty (not the originally requested quantity) so a partial fill's own holdings
        // change is exactly as honest as its cash-flow change.
        if (baseAsset != null) {
            BigDecimal assetDelta = isBuy ? executedQty : executedQty.negate();
            simulatedAssetBalances.computeIfAbsent(baseAsset, k -> new java.util.concurrent.atomic.AtomicReference<>(BigDecimal.ZERO))
                .updateAndGet(qty -> qty.add(assetDelta));
        }
        persistBalances(newBalance, snapshotAssetBalances()); // durable, not just in-memory
        log.info("[PAPER] Simulated {} fill with slippage: {} {} of {} requested {} @ {} (observed {}, fee {}, brokerOrderId={}, balance now {})",
            resultStatus, request.side(), executedQty, requestedQty, request.symbol(), fillPrice, observedPrice, fee, brokerOrderId, simulatedUsdtBalance.get());
        // Records this order's real, just-computed outcome, not a hardcoded "FILLED" literal, so
        // a later status lookup finds the actual simulated result.
        OrderStatusInfo statusInfo = new OrderStatusInfo(resultStatus, executedQty, fillPrice, "{}");
        simulatedOrderStatusByBrokerOrderId.put(brokerOrderId, statusInfo);
        if (request.clientOrderId() != null) {
            simulatedOrderStatusByClientOrderId.put(request.clientOrderId(), statusInfo);
        }
        return new OrderResult(true, brokerOrderId, request.clientOrderId(), resultStatus,
            executedQty, fillPrice, "{}", null);
    }

    @Override
    public OrderResult cancelOrder(String apiKey, String apiSecret, BrokerMode mode, String symbol, String brokerOrderId) {
        // Every simulated market order already filled completely and immediately at placement
        // time -- there is never anything left open to cancel.
        return new OrderResult(false, brokerOrderId, null, "FILLED", null, null, "{}",
            "This paper order already filled immediately at placement -- nothing left to cancel.");
    }

    @Override
    public List<OpenOrderInfo> getOpenOrders(String apiKey, String apiSecret, BrokerMode mode) {
        // Market orders never stay open (see cancelOrder's own comment). OCO legs are tracked
        // separately via PaperOco/getOcoStatus, not through this method in this codebase's own
        // actual usage of it.
        return List.of();
    }

    @Override
    public List<OpenOrderInfo> getOpenOrders(String apiKey, String apiSecret, BrokerMode mode, String symbol) {
        // Same reasoning as the unfiltered overload above -- nothing ever stays open in PAPER.
        return List.of();
    }

    @Override
    public OcoOrderResult placeExitOco(String apiKey, String apiSecret, BrokerMode mode, String symbol,
                                        BigDecimal quantity, BigDecimal takeProfitPrice, BigDecimal stopLossTriggerPrice,
                                        BigDecimal stopLimitPrice, String listClientOrderId) {
        PaperOco oco = new PaperOco();
        oco.setSymbol(symbol);
        oco.setQuantity(quantity);
        oco.setTakeProfitPrice(takeProfitPrice);
        oco.setStopLossPrice(stopLossTriggerPrice);
        oco.setListClientOrderId(listClientOrderId);
        oco = paperOcoRepo.save(oco);
        log.info("[PAPER] Simulated OCO placed: {} qty={} TP={} SL={} (ocoOrderListId={})",
            symbol, quantity, takeProfitPrice, stopLossTriggerPrice, oco.getId());
        return new OcoOrderResult(true, oco.getId(), "{}", null);
    }

    @Override
    public OcoStatusInfo getOcoStatusByClientOrderId(String apiKey, String apiSecret, BrokerMode mode, String listClientOrderId) {
        var oco = paperOcoRepo.findByListClientOrderId(listClientOrderId).orElse(null);
        if (oco == null) {
            return new OcoStatusInfo(null, "REJECT", List.of(), "{}");
        }
        return getOcoStatus(apiKey, apiSecret, mode, oco.getId());
    }

    @Override
    public OcoStatusInfo getOcoStatus(String apiKey, String apiSecret, BrokerMode mode, String orderListId) {
        var oco = paperOcoRepo.findById(orderListId).orElse(null);
        if (oco == null) {
            return new OcoStatusInfo(null, "REJECT", List.of(), "{}");
        }
        if (!"NEW".equals(oco.getStatus())) {
            // Already resolved on a prior check -- report the same outcome again, idempotently.
            return ocoStatusFor(oco);
        }
        BigDecimal currentPrice = realAdapterForMarketData.getCurrentPrice(oco.getSymbol(), BrokerMode.LIVE);
        if (currentPrice == null) {
            return new OcoStatusInfo(oco.getId(), "EXECUTING", List.of(activeLeg(oco)), "{}"); // can't check right now -- stays active, never guessed
        }
        // A LONG position's protective OCO: TP is ABOVE entry, SL is BELOW -- triggered when
        // price reaches either side. This mirrors exactly what a real Binance OCO does.
        if (currentPrice.compareTo(oco.getTakeProfitPrice()) >= 0) {
            oco.setStatus("TP_FILLED");
        } else if (currentPrice.compareTo(oco.getStopLossPrice()) <= 0) {
            // Crossing the SL trigger doesn't always resolve straight to SL_FILLED: PAPER mode
            // reproduces the real-world scenario BinanceBrokerAdapter.placeExitOco's own
            // STOP_LOSS_LIMIT (a 0.5% resting-limit buffer below the trigger) and
            // PositionMonitorService.handleStopTriggeredButUnfilled's watchdog exist to defend
            // against: a fast move can blow straight through that resting limit, triggering the
            // stop without actually filling it. Simulated the same way that real gap happens --
            // when the market has already moved a conservative buffer past the trigger (not just
            // barely touched it) by the time this check observes it, the leg is left
            // triggered-but-unfilled instead of auto-resolving, so PAPER mode exercises the same
            // emergency-flatten/watchdog path a real account would need.
            BigDecimal gapBuffer = oco.getStopLossPrice().multiply(STOP_LIMIT_NON_FILL_GAP_RATE);
            if (currentPrice.compareTo(oco.getStopLossPrice().subtract(gapBuffer)) < 0) {
                oco.setStatus("SL_TRIGGERED_UNFILLED");
                paperOcoRepo.save(oco);
                log.warn("[PAPER] Simulated stop-limit leg for OCO {} TRIGGERED but did NOT fill -- price {} gapped past the stop-limit's "
                        + "own resting buffer below trigger {} ({}%), exactly the real-world gap BinanceBrokerAdapter's own STOP_LOSS_LIMIT "
                        + "buffer can suffer. Left unresolved for the emergency-flatten watchdog to find, not auto-filled.",
                    orderListId, currentPrice, oco.getStopLossPrice(), STOP_LIMIT_NON_FILL_GAP_RATE.multiply(BigDecimal.valueOf(100)));
                return ocoStatusFor(oco);
            }
            oco.setStatus("SL_FILLED");
        }
        if (!"NEW".equals(oco.getStatus())) {
            oco.setResolvedAt(java.time.LocalDateTime.now());
            // Records the real observed market price this OCO resolved at, not the stored
            // trigger price -- a fast-moving market can gap past a trigger before this check
            // ever observes it, and a real Binance stop fills at whatever price it actually
            // executes at, not the trigger price itself.
            oco.setResolvedPrice(currentPrice);
            paperOcoRepo.save(oco);
            log.info("[PAPER] Simulated OCO {} resolved: {} at market price {}", orderListId, oco.getStatus(), currentPrice);
        }
        return ocoStatusFor(oco);
    }

    private OcoStatusInfo ocoStatusFor(PaperOco oco) {
        boolean tpFilled = "TP_FILLED".equals(oco.getStatus());
        boolean slFilled = "SL_FILLED".equals(oco.getStatus());
        // SL_TRIGGERED_UNFILLED is deliberately not ALL_DONE and its own leg deliberately does
        // not report FILLED -- that combination is exactly what
        // PositionMonitorService.handleStopTriggeredButUnfilled's watchdog keys off of to detect
        // a real triggered-but-unfilled stop and emergency-flatten the position. Reporting
        // ALL_DONE/FILLED here instead would make that entire safety path untestable in PAPER
        // mode.
        String listStatus = (tpFilled || slFilled) ? "ALL_DONE" : "EXECUTING";
        // A filled leg reports the real observed price this OCO resolved at (which can differ
        // from its own stored trigger price on a gap) when that's available, falling back to the
        // trigger price only for a legacy/still-unresolved record with no resolvedPrice recorded.
        BigDecimal tpFillPrice = tpFilled && oco.getResolvedPrice() != null ? oco.getResolvedPrice() : oco.getTakeProfitPrice();
        BigDecimal slFillPrice = slFilled && oco.getResolvedPrice() != null ? oco.getResolvedPrice() : oco.getStopLossPrice();
        var legs = List.of(
            new OcoStatusInfo.Leg(oco.getId() + "-TP", "SELL", "LIMIT_MAKER", tpFilled ? "FILLED" : "NEW",
                tpFillPrice, tpFilled ? oco.getQuantity() : BigDecimal.ZERO, oco.getQuantity()),
            // Leg status stays "NEW" (never "FILLED") for SL_TRIGGERED_UNFILLED -- see this
            // method's own updated comment above for why that distinction matters.
            new OcoStatusInfo.Leg(oco.getId() + "-SL", "SELL", "STOP_LOSS", slFilled ? "FILLED" : "NEW",
                slFillPrice, slFilled ? oco.getQuantity() : BigDecimal.ZERO, oco.getQuantity()));
        return new OcoStatusInfo(oco.getId(), listStatus, legs, "{}");
    }

    private OcoStatusInfo.Leg activeLeg(PaperOco oco) {
        return new OcoStatusInfo.Leg(oco.getId() + "-TP", "SELL", "LIMIT_MAKER", "NEW", oco.getTakeProfitPrice(), BigDecimal.ZERO, oco.getQuantity());
    }

    @Override
    public OcoOrderResult cancelOco(String apiKey, String apiSecret, BrokerMode mode, String symbol, String orderListId) {
        var oco = paperOcoRepo.findById(orderListId).orElse(null);
        // SL_TRIGGERED_UNFILLED must be cancellable too -- this is exactly the state
        // PositionSafetyService.emergencyFlatten's real-world flow cancels the stuck exit OCO
        // from before placing a fresh market sell to actually get out of the position. Guarding
        // on "NEW" alone would make that cancel silently do nothing for this case.
        if (oco != null && ("NEW".equals(oco.getStatus()) || "SL_TRIGGERED_UNFILLED".equals(oco.getStatus()))) {
            oco.setStatus("CANCELLED");
            oco.setResolvedAt(java.time.LocalDateTime.now());
            paperOcoRepo.save(oco);
        }
        return new OcoOrderResult(true, orderListId, "{}", null);
    }

    @Override
    public OrderStatusInfo getOrderStatus(String apiKey, String apiSecret, BrokerMode mode, String symbol, String brokerOrderId) {
        // Reads back this order's real, recorded simulated outcome -- PARTIALLY_FILLED stays
        // PARTIALLY_FILLED on a later check, never silently upgraded to FILLED.
        //
        // The fallback for a brokerOrderId this instance never recorded is UNKNOWN, not an
        // unconditional "FILLED" with a null executedQty -- a self-contradictory, fabricated-
        // looking result that would silently claim a real fill for an order this adapter has no
        // actual record of, the same class of bug BinanceBrokerAdapter's own resolveExecutedQty
        // is built to avoid for the real adapter. This codebase's AutoTradeService already raises
        // a CRITICAL incident and halts on an UNKNOWN broker result rather than assuming a fill
        // happened, so routing a genuinely untracked PAPER order into that same existing safety
        // path is correct. A genuine "never recorded" lookup (an OCO leg's own id, handled
        // through getOcoStatus instead, or an order placed against a since-restarted, different
        // adapter instance) should surface as exactly that -- unknown -- not be quietly reported
        // as a successful fill.
        return simulatedOrderStatusByBrokerOrderId.getOrDefault(brokerOrderId, new OrderStatusInfo("UNKNOWN", null, null, "{}"));
    }

    @Override
    public OrderStatusInfo getOrderStatusByClientOrderId(String apiKey, String apiSecret, BrokerMode mode, String symbol, String clientOrderId) {
        // Same reasoning as getOrderStatus immediately above.
        return simulatedOrderStatusByClientOrderId.getOrDefault(clientOrderId, new OrderStatusInfo("UNKNOWN", null, null, "{}"));
    }

    @Override
    public List<Fill> getFillsForOrder(String apiKey, String apiSecret, BrokerMode mode, String symbol, String orderId) {
        // No real fill data exists for a simulated order. Callers (FillLedgerService) only need
        // some record to exist for their own accounting, but an empty list here is more honest
        // than a fabricated Fill, since this class has no record of the original order's own
        // price/quantity to reconstruct one from at this call site -- callers already treat a
        // fill-ledger gap as non-fatal.
        return List.of();
    }

    @Override
    public SymbolRules getSymbolRules(String symbol, BrokerMode mode) {
        return realAdapterForMarketData.getSymbolRules(symbol, BrokerMode.LIVE);
    }

    @Override
    public BigDecimal getCurrentPrice(String symbol, BrokerMode mode) {
        return realAdapterForMarketData.getCurrentPrice(symbol, BrokerMode.LIVE);
    }

    @Override
    public SpreadInfo getSpread(String symbol, BrokerMode mode) {
        return realAdapterForMarketData.getSpread(symbol, BrokerMode.LIVE);
    }

    @Override
    public long getClockDriftMs() {
        return realAdapterForMarketData.getClockDriftMs();
    }

    @Override
    public OrderBookDepth getOrderBookDepth(String symbol, BrokerMode mode, int limit) {
        return realAdapterForMarketData.getOrderBookDepth(symbol, BrokerMode.LIVE, limit);
    }

    @Override
    public List<com.tradevision.service.broker.dto.Candle> getRecentCandles(String symbol, String interval, int limit, BrokerMode mode) {
        return realAdapterForMarketData.getRecentCandles(symbol, interval, limit, BrokerMode.LIVE);
    }

    @Override
    public List<String> getAllTradableUsdtSymbols(BrokerMode mode) {
        return realAdapterForMarketData.getAllTradableUsdtSymbols(BrokerMode.LIVE);
    }

    @Override
    public List<TickerStats> getAll24hrTickers(BrokerMode mode) {
        return realAdapterForMarketData.getAll24hrTickers(BrokerMode.LIVE);
    }

    @Override
    public BigDecimal getHistoricalPrice(String symbol, long timestampMillis, BrokerMode mode) {
        return realAdapterForMarketData.getHistoricalPrice(symbol, timestampMillis, BrokerMode.LIVE);
    }

    @Override
    public String createListenKey(String apiKey, String apiSecret, BrokerMode mode) {
        // PAPER mode has no real user-data stream to subscribe to -- position/order management
        // already works entirely through PositionMonitorService's own REST-reconciliation
        // polling, which works correctly for PAPER since getOrderStatus/getOcoStatus above are
        // properly simulated. A fake, stable key is returned so callers that merely check for a
        // non-null value don't misbehave; nothing ever actually uses it to open a connection for
        // a PAPER credential specifically.
        return "PAPER-NO-STREAM";
    }

    @Override
    public void keepAliveListenKey(String apiKey, String apiSecret, String listenKey, BrokerMode mode) {
        // No-op -- see createListenKey's own comment.
    }

    @Override
    public void closeListenKey(String apiKey, String apiSecret, String listenKey, BrokerMode mode) {
        // No-op -- see createListenKey's own comment.
    }
}
