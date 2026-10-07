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
 * Review finding ("No pure paper-trading mode with full isolation" -- external review,
 * eighteenth pass, P0, confirmed real by direct inspection before this was built: TESTNET was
 * the only simulation surface, and a misconfigured credential or a testnet-specific behavior
 * difference could still move real money or produce false confidence): the actual PAPER mode.
 *
 * DESIGN, stated plainly:
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
     * P2-4 fix ("in-memory balance resets to 100k on restart" -- external review, confirmed real
     * by direct inspection: simulatedUsdtBalance below had no durable backing at all before this
     * fix -- every restart/redeploy silently reset every PAPER credential's own running balance
     * back to a flat 100,000 with no record it ever happened, exactly the kind of quiet data loss
     * that makes a paper-trading track record meaningless, see PaperAccountBalance's own class
     * javadoc). May be null for the one instance BrokerCredentialService keeps purely for
     * connect-time validation (doConnect has no credentialId yet at that point -- the credential
     * hasn't been saved), which never processes a real simulated fill anyway, so that instance's
     * own balance is never meaningfully read or persisted either way.
     */
    private final PaperAccountBalanceRepository paperAccountBalanceRepo;
    /**
     * P2-4 fix, same context as paperAccountBalanceRepo's own field javadoc above: the PAPER
     * credential this specific adapter instance's balance belongs to -- BrokerCredentialService's
     * own paperBrokerAdaptersByCredential map (see that field's own javadoc, "Paper trading
     * remains shared between users") already gives each PAPER credential its own adapter
     * instance, so this is simply that same key, threaded through so THIS instance can load and
     * persist its own durable balance document under it. Null only for the connect-validation-
     * only instance described above.
     */
    private final String credentialId;
    /**
     * Review finding ("Paper trading balance is fixed" -- external review, twenty-third pass,
     * P2, confirmed real by direct inspection before this fix: getBalance() always returned a
     * flat 100_000, regardless of how many simulated positions were open or what their realized
     * P&L was): the actual fix, scoped to what's genuinely achievable given a real, confirmed
     * constraint -- BrokerAdapter.getBalance(apiKey, apiSecret, mode) has no credentialId
     * parameter at all (apiKey/apiSecret are placeholder values for PAPER credentials, never
     * read for identification -- see this class's own header javadoc), and this method has 9
     * real call sites across 7 files sharing that same interface with BinanceBrokerAdapter.
     * Widening that interface for every PAPER credential to get its own tracked balance would
     * mean touching all of them, a materially larger and riskier change than this fix attempts.
     * A real, internally-tracked running balance, updated by every simulated fill's own actual
     * cash flow -- and, per this class's own updated P2-4 fix above, now durably persisted per
     * PAPER credential rather than reset to this same starting value on every restart. Only a
     * genuinely new PAPER credential (or the connect-validation-only instance, which never
     * persists) actually starts at this disclosed 100,000 default.
     */
    private final java.util.concurrent.atomic.AtomicReference<BigDecimal> simulatedUsdtBalance;
    /**
     * Audit finding (P1-4 -- "Improve PaperBrokerAdapter realism... per-asset balances,
     * oversell rejection" -- full context in PaperAccountBalance.assetBalances' own field
     * javadoc and placeOrder's own updated comment below): the per-base-asset counterpart to
     * simulatedUsdtBalance above, keyed by uppercase base asset symbol (e.g. "BTC"). A BUY
     * increases the relevant entry; a SELL is rejected outright (same "refuse a fill a real
     * exchange would reject" posture as the existing USDT insufficient-balance check) unless
     * this credential's own tracked holdings genuinely cover the requested quantity, and
     * decreases that entry by exactly what filled.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicReference<BigDecimal>> simulatedAssetBalances;
    /**
     * Review finding ("PaperBrokerAdapter still reports simulated market orders as effectively
     * filled during later status lookup even though v178 can simulate partial fills" -- external
     * review, thirtieth pass, P2, confirmed real by direct inspection before this fix:
     * getOrderStatus/getOrderStatusByClientOrderId both unconditionally returned "FILLED",
     * regardless of what placeOrder had actually simulated for that same order -- a genuine
     * PARTIALLY_FILLED result from placeOrder would then read back as fully FILLED on any
     * subsequent status check, e.g. during reconciliation, giving a misleadingly optimistic test
     * result exactly as the review describes): the actual fix -- placeOrder's own real simulated
     * outcome is recorded here, keyed by both ids a caller might look it up by, and read back
     * by the two status methods instead of a hardcoded literal.
     */
    private final java.util.Map<String, OrderStatusInfo> simulatedOrderStatusByBrokerOrderId = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, OrderStatusInfo> simulatedOrderStatusByClientOrderId = new java.util.concurrent.ConcurrentHashMap<>();
    // Binance's own standard spot taker fee (0.1%) -- applied to every simulated fill's notional
    // value, same as a real market order would actually incur.
    private static final BigDecimal SIMULATED_TAKER_FEE_RATE = BigDecimal.valueOf(0.001);
    /**
     * Review finding ("Paper fills are unrealistically perfect" -- external review, twenty-third
     * pass, P2, confirmed real by direct inspection before this fix: every simulated market
     * order filled 100% instantly at the exact current price, with zero slippage): a small,
     * disclosed, fixed slippage applied against the requester -- a BUY fills slightly above the
     * observed price, a SELL fills slightly below it, the same direction real slippage would
     * actually move against a market order's own requester. 5 basis points (0.05%) is a
     * reasonable, conservative approximation for a liquid USDT pair -- not a claim of matching
     * any specific real order book's actual depth, which this simulation has no access to model
     * (see this class's own header javadoc on why partial fills/order-book depth remain
     * unmodeled -- a genuinely deeper simulation needs real order-book data this class does not
     * currently fetch).
     */
    private static final BigDecimal SIMULATED_SLIPPAGE_RATE = BigDecimal.valueOf(0.0005);

    /**
     * Review finding ("Paper fills are unrealistically perfect" / "Add slippage/partial-fill
     * simulation" -- external review, P2 and P3, full context in SIMULATED_SLIPPAGE_RATE's own
     * field javadoc above): the actual partial-fill piece that was still missing after
     * slippage was added -- every simulated order still filled 100% every time, regardless of
     * size. Scaled by notional value: larger orders have a real, though modest, chance of not
     * filling completely, the same market-impact intuition a real order book would produce.
     * Genuinely safe to simulate this way -- OrderService.recordBrokerResult and everything
     * downstream of it already correctly handles a PARTIALLY_FILLED broker result, since the
     * real BinanceBrokerAdapter already produces this exact status for real fills; this is not
     * a new code path, just a new way to reach an already-handled one.
     */
    private static final BigDecimal PARTIAL_FILL_NOTIONAL_THRESHOLD = BigDecimal.valueOf(5000);
    /**
     * Audit finding (P1-4 -- "Improve PaperBrokerAdapter realism... stop-limit-non-fill
     * simulation" -- full context in getOcoStatus's own updated comment): matches
     * BinanceBrokerAdapter.placeExitOco's own real STOP_LOSS_LIMIT buffer exactly (that method's
     * own javadoc: belowPrice = stopTrigger * 0.995, i.e. a 0.5% resting-limit gap below the
     * trigger) -- the real-world distance a fast price move has to cross past the trigger before
     * the resting limit leg is left behind, unfilled. Reused here as the threshold for when a
     * simulated SL crossing is left SL_TRIGGERED_UNFILLED instead of auto-resolving to
     * SL_FILLED, so the simulated gap matches the real gap it's modeling, not an arbitrary one.
     */
    private static final BigDecimal STOP_LIMIT_NON_FILL_GAP_RATE = BigDecimal.valueOf(0.005);
    /**
     * Review finding, same context as PARTIAL_FILL_NOTIONAL_THRESHOLD's own javadoc above: a
     * real, INJECTABLE random source -- deliberately java.util.Random, not ThreadLocalRandom,
     * since ThreadLocalRandom cannot be seeded or replaced at all, which would have made this
     * partial-fill simulation genuinely untestable and, worse, would have made EXISTING tests
     * asserting an exact executedQty flaky (confirmed directly: two existing tests use a 0.1 BTC
     * @ 65000 order, a ~$6500 notional that crosses this exact threshold -- without this fix,
     * those tests would have had a real, non-zero chance of failing on any given run). Defaults
     * to genuine randomness in production; setRandomForTesting below lets tests force
     * deterministic behavior instead of relying on statistical luck.
     */
    private java.util.Random random = new java.util.Random();

    /** Package-private, test-only -- see the random field's own javadoc for why this exists. */
    void setRandomForTesting(java.util.Random random) {
        this.random = random;
    }

    /**
     * P2-4 fix, full context in credentialId's own field javadoc above: the connect-validation-
     * only construction path (BrokerCredentialService.doConnect) and any existing test that
     * genuinely has no PAPER credential yet -- balance is in-memory-only for the lifetime of
     * this instance, exactly as every PaperBrokerAdapter instance behaved before this fix.
     */
    public PaperBrokerAdapter(BrokerAdapter realAdapterForMarketData, PaperOcoRepository paperOcoRepo) {
        this(realAdapterForMarketData, paperOcoRepo, null, null);
    }

    /**
     * P2-4 fix, full context in paperAccountBalanceRepo/credentialId's own field javadocs above:
     * the real construction path -- BrokerCredentialService.adapterForCredential passes a real
     * repo and this PAPER credential's own id, so this instance loads whatever durable balance
     * already exists for it (a restart/redeploy resuming a real paper-trading track record
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
                // Audit finding (P1-4, full context in PaperAccountBalance.assetBalances' own
                // field javadoc): resumed here too, same reasoning as the USDT balance just
                // above -- a legacy record saved before this fix has no assetBalances field at
                // all (defaults to an empty map on deserialization), which is the correct,
                // honest starting point for a credential that has never had holdings tracked.
                if (existing.get().getAssetBalances() != null) initialAssetBalances.putAll(existing.get().getAssetBalances());
                log.info("[PAPER] Resumed durable balance {} USDT and {} asset holding(s) for credential {} (not reset -- see this class's own P2-4/P1-4 fixes)",
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
     * P2-4 fix, full context above, extended by P1-4 (full context in
     * PaperAccountBalance.assetBalances' own field javadoc): upserts this credential's own
     * durable balance document, USDT cash and per-asset holdings together. No-op for the
     * connect-validation-only instance (paperAccountBalanceRepo/credentialId null).
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

    // Review finding (P1 #9, full context in BrokerAdapter.getAccountUid's own javadoc): a PAPER
    // credential is never actually tied to a real Binance account at all -- any placeholder
    // apiKey/apiSecret works, per this class's own header javadoc. A single constant identity so
    // rotation's own account-identity check (which only ever runs for real accounts) is never
    // spuriously triggered for PAPER, without pretending this simulates a real per-account
    // identity it has no way to actually verify.
    @Override
    public String getAccountUid(String apiKey, String apiSecret, BrokerMode mode) {
        return "PAPER";
    }

    // Review finding (P1 #10, full context in BrokerAdapter.getApiKeyRestrictions' own javadoc):
    // a PAPER credential has no real key restrictions to report -- simulated, unconditionally
    // safe, mirroring getAccountPermissions' own simulated-safe values just above.
    @Override
    public ApiKeyRestrictions getApiKeyRestrictions(String apiKey, String apiSecret, BrokerMode mode) {
        return new ApiKeyRestrictions(true, false, false, false, true);
    }

    @Override
    public List<AssetBalance> getBalance(String apiKey, String apiSecret, BrokerMode mode) {
        // Review finding ("Paper trading balance is fixed" -- full context in
        // simulatedUsdtBalance's own field javadoc above): genuinely tracked per PAPER credential
        // (P2-4 fix, same context as credentialId's own field javadoc: BrokerCredentialService
        // gives each PAPER credential its own adapter instance), updated by every simulated
        // fill's own real cash flow -- see placeOrder below -- and durably persisted across
        // restarts (P2-4 fix, full context in paperAccountBalanceRepo's own field javadoc).
        return List.of(new AssetBalance("USDT", simulatedUsdtBalance.get(), BigDecimal.ZERO));
    }

    @Override
    public OrderResult placeOrder(String apiKey, String apiSecret, BrokerMode mode, OrderRequest request) {
        BigDecimal observedPrice = realAdapterForMarketData.getCurrentPrice(request.symbol(), BrokerMode.LIVE);
        if (observedPrice == null) {
            return new OrderResult(false, null, request.clientOrderId(), "REJECTED", null, null, "{}",
                "Paper trading could not fetch a real current price for " + request.symbol() + " to simulate this fill.");
        }
        // P2-4 fix ("no step-size rounding" -- external review, confirmed real by direct
        // inspection: this method used to fill request.quantity() exactly as given, never
        // rounded to the symbol's own real stepSize the way BinanceBrokerAdapter.doPlaceOrder
        // always does before ever placing a real order -- meaning a simulated fill could report
        // an executedQty a real Binance order for the same symbol could never actually produce,
        // undermining paper trading's own stated purpose as "a meaningful rehearsal of real
        // strategy behavior" (this class's own header javadoc). Reuses getSymbolRules below
        // (already delegated to the real adapter, per this class's own header javadoc) rather
        // than duplicating rounding logic -- same roundDownToStep semantics as the real adapter,
        // applied here too. Fails open (falls back to the unrounded quantity) if the rules lookup
        // itself fails, consistent with this class's own existing "never let a lookup failure
        // block an otherwise-good simulated fill" posture elsewhere (see the price-fetch
        // try/catch already established in BinanceBrokerAdapter for the same reasoning).
        BigDecimal requestedQty = request.quantity();
        // Audit finding (P1-4 -- "per-asset balances, oversell rejection" -- full context in
        // simulatedAssetBalances' own field javadoc): captured here, alongside the existing
        // step-size lookup that already fetches the same SymbolRules, rather than a second
        // lookup. Left null (same fail-open posture as the step-size rounding immediately below)
        // if the lookup itself fails -- the oversell check further down simply cannot be
        // enforced for this one fill if that happens, consistent with this class's own
        // established "never let a market-data lookup failure block an otherwise-good simulated
        // fill" posture elsewhere in this method.
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
        // Review finding ("Paper fills are unrealistically perfect" -- full context in
        // SIMULATED_SLIPPAGE_RATE's own field javadoc above): slippage moves AGAINST the
        // requester's own direction -- a BUY fills slightly higher than observed, a SELL fills
        // slightly lower, matching the actual direction real market-order slippage moves.
        boolean isBuy = "BUY".equalsIgnoreCase(request.side());
        BigDecimal slippageMultiplier = isBuy
            ? BigDecimal.ONE.add(SIMULATED_SLIPPAGE_RATE)
            : BigDecimal.ONE.subtract(SIMULATED_SLIPPAGE_RATE);
        BigDecimal fillPrice = observedPrice.multiply(slippageMultiplier).setScale(8, java.math.RoundingMode.HALF_UP);
        // P2-4 fix ("can go negative" -- external review, confirmed real by direct inspection:
        // simulatedUsdtBalance had no floor at all -- a BUY was always simulated as filling in
        // full regardless of the tracked balance, so it could go arbitrarily negative, something
        // a real exchange would reject outright with an insufficient-balance error). Checked
        // against the FULL requested notional (before any partial-fill roll below, which can only
        // ever reduce the actual cash needed) so this can never itself be the reason a fill later
        // turns out to have spent more than was actually available.
        if (isBuy) {
            BigDecimal fullNotional = requestedQty.multiply(fillPrice);
            BigDecimal fullFee = fullNotional.multiply(SIMULATED_TAKER_FEE_RATE);
            if (simulatedUsdtBalance.get().compareTo(fullNotional.add(fullFee)) < 0) {
                return new OrderResult(false, null, request.clientOrderId(), "REJECTED", null, null, "{}",
                    "Paper trading balance " + simulatedUsdtBalance.get() + " USDT is insufficient for this order's simulated cost "
                        + fullNotional.add(fullFee) + " USDT -- refusing to simulate a fill a real exchange would reject.");
            }
        } else if (baseAsset != null) {
            // Audit finding (P1-4 -- "per-asset balances, oversell rejection" -- full context in
            // simulatedAssetBalances' own field javadoc): the real, confirmed gap this fix
            // closes -- before this, a SELL was simulated as filling regardless of whether this
            // PAPER credential had ever actually bought any of this asset at all. Checked against
            // the FULL requested quantity (before any partial-fill roll below, which can only
            // ever reduce the actual quantity needed), same "can never itself be the reason a
            // fill later turns out to have sold more than was actually held" reasoning as the
            // BUY-side USDT check just above.
            BigDecimal held = simulatedAssetBalances.getOrDefault(baseAsset, new java.util.concurrent.atomic.AtomicReference<>(BigDecimal.ZERO)).get();
            if (held.compareTo(requestedQty) < 0) {
                return new OrderResult(false, null, request.clientOrderId(), "REJECTED", null, null, "{}",
                    "Paper trading holdings of " + held + " " + baseAsset + " are insufficient to sell " + requestedQty
                        + " -- refusing to simulate a fill a real exchange would reject as an oversell.");
            }
        }
        String brokerOrderId = "PAPER-" + UUID.randomUUID();
        // Review finding, same context as PARTIAL_FILL_NOTIONAL_THRESHOLD's own field javadoc
        // above: the actual partial-fill roll. Only orders above the notional threshold are
        // ever eligible at all -- small orders always fill completely, matching real liquidity
        // intuition (a $50 market order essentially never partial-fills; a $50,000 one might).
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
        // Review finding ("Paper trading balance is fixed" -- full context in
        // simulatedUsdtBalance's own field javadoc above): the actual balance update. A BUY
        // spends USDT (notional + fee), a SELL receives USDT (notional - fee) -- the same net
        // cash-flow direction a real spot fill actually produces, correct regardless of whether
        // this specific order is an entry or an exit from this adapter's own point of view (it
        // has no opinion on that; Position/Order-level entry/exit semantics belong to the
        // caller, not this method). Uses executedQty now, not the originally requested
        // quantity, so a partial fill's own cash flow is honest too.
        BigDecimal fee = notional.multiply(SIMULATED_TAKER_FEE_RATE);
        BigDecimal cashFlow = isBuy ? notional.add(fee).negate() : notional.subtract(fee);
        BigDecimal newBalance = simulatedUsdtBalance.updateAndGet(bal -> bal.add(cashFlow));
        // Audit finding (P1-4, full context in simulatedAssetBalances' own field javadoc): the
        // actual per-asset holdings update, mirroring the USDT cash-flow update immediately
        // above -- a BUY increases holdings by what actually filled, a SELL decreases them,
        // using executedQty (not the originally requested quantity) so a partial fill's own
        // holdings change is exactly as honest as its cash-flow change already is.
        if (baseAsset != null) {
            BigDecimal assetDelta = isBuy ? executedQty : executedQty.negate();
            simulatedAssetBalances.computeIfAbsent(baseAsset, k -> new java.util.concurrent.atomic.AtomicReference<>(BigDecimal.ZERO))
                .updateAndGet(qty -> qty.add(assetDelta));
        }
        persistBalances(newBalance, snapshotAssetBalances()); // P2-4/P1-4 fix: durable now, not just in-memory
        log.info("[PAPER] Simulated {} fill with slippage: {} {} of {} requested {} @ {} (observed {}, fee {}, brokerOrderId={}, balance now {})",
            resultStatus, request.side(), executedQty, requestedQty, request.symbol(), fillPrice, observedPrice, fee, brokerOrderId, simulatedUsdtBalance.get());
        // Review finding ("PaperBrokerAdapter still reports simulated market orders as
        // effectively filled during later status lookup" -- external review, thirtieth pass,
        // P2, full context in simulatedOrderStatusByBrokerOrderId's own field javadoc): the
        // actual recording -- this order's own real, just-computed outcome, not a hardcoded
        // "FILLED" literal, is what a later status lookup will now find.
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
            // Audit finding (P1-4 -- "Improve PaperBrokerAdapter realism... stop-limit-non-fill
            // simulation" -- full context in STOP_LIMIT_NON_FILL_GAP_RATE's own field javadoc):
            // before this fix, crossing the SL trigger always resolved straight to SL_FILLED --
            // PAPER mode could never reproduce the exact real-world scenario BinanceBrokerAdapter.
            // placeExitOco's own STOP_LOSS_LIMIT (a 0.5% resting-limit buffer below the trigger)
            // and PositionMonitorService.handleStopTriggeredButUnfilled/the P0-3 watchdog exist to
            // defend against: a fast move blows straight through that resting limit, triggering
            // the stop without actually filling it. Simulated here the same way that real gap
            // happens -- when the market has already moved a genuinely conservative buffer PAST
            // the trigger (not just barely touched it) by the time this check observes it, the
            // leg is left triggered-but-unfilled instead of auto-resolving, so PAPER mode
            // exercises the exact same emergency-flatten/watchdog path a real account would need.
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
            // P2-4 fix ("SL fills at exact SL (no gap)" -- full context in PaperOco.resolvedPrice's
            // own field javadoc): records the REAL observed market price this OCO resolved at,
            // not the stored trigger price -- a fast-moving market can genuinely gap past a
            // trigger before this check ever observes it, and a real Binance stop would fill at
            // whatever price it actually executes at, not the trigger price itself.
            oco.setResolvedPrice(currentPrice);
            paperOcoRepo.save(oco);
            log.info("[PAPER] Simulated OCO {} resolved: {} at market price {}", orderListId, oco.getStatus(), currentPrice);
        }
        return ocoStatusFor(oco);
    }

    private OcoStatusInfo ocoStatusFor(PaperOco oco) {
        boolean tpFilled = "TP_FILLED".equals(oco.getStatus());
        boolean slFilled = "SL_FILLED".equals(oco.getStatus());
        // Audit finding (P1-4, full context in getOcoStatus's own updated comment above):
        // SL_TRIGGERED_UNFILLED is deliberately NOT ALL_DONE and its own leg deliberately does
        // NOT report FILLED -- that combination is exactly what
        // PositionMonitorService.handleStopTriggeredButUnfilled (and the P0-3 watchdog that
        // calls it every 10s) keys off of to detect a real triggered-but-unfilled stop and
        // emergency-flatten the position. Reporting ALL_DONE/FILLED here instead would make that
        // entire safety path untestable in PAPER mode.
        String listStatus = (tpFilled || slFilled) ? "ALL_DONE" : "EXECUTING";
        // P2-4 fix, full context in PaperOco.resolvedPrice's own field javadoc: a filled leg
        // reports the real observed price this OCO resolved at (which can genuinely differ from
        // its own stored trigger price on a gap) when that's available, falling back to the
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
        // Audit finding (P1-4, full context in getOcoStatus's own updated comment above):
        // SL_TRIGGERED_UNFILLED must be cancellable too -- this is exactly the state
        // PositionSafetyService.emergencyFlatten's own real-world flow cancels the stuck exit
        // OCO from before placing a fresh market sell to actually get out of the position.
        // Leaving this guard at "NEW" only (its state before this fix) would have made that
        // cancel silently do nothing for the one case this fix exists to simulate.
        if (oco != null && ("NEW".equals(oco.getStatus()) || "SL_TRIGGERED_UNFILLED".equals(oco.getStatus()))) {
            oco.setStatus("CANCELLED");
            oco.setResolvedAt(java.time.LocalDateTime.now());
            paperOcoRepo.save(oco);
        }
        return new OcoOrderResult(true, orderListId, "{}", null);
    }

    @Override
    public OrderStatusInfo getOrderStatus(String apiKey, String apiSecret, BrokerMode mode, String symbol, String brokerOrderId) {
        // Review finding ("PaperBrokerAdapter still reports simulated market orders as
        // effectively filled during later status lookup" -- external review, thirtieth pass,
        // P2, full context in simulatedOrderStatusByBrokerOrderId's own field javadoc): reads
        // back this order's own real, recorded simulated outcome -- PARTIALLY_FILLED stays
        // PARTIALLY_FILLED on a later check, not silently upgraded to FILLED.
        //
        // P2-4 fix ("unknown order IDs default to FILLED with null qty" -- external review,
        // confirmed real by direct inspection: the fallback for a brokerOrderId this instance
        // genuinely never recorded used to be an unconditional "FILLED" with a null executedQty
        // -- a self-contradictory, fabricated-looking result that silently claims a real fill
        // for an order this adapter has no actual record of, exactly the class of bug
        // BinanceBrokerAdapter's own resolveExecutedQty was built to eliminate for the real
        // adapter ("Binance adapter can fabricate a full fill" -- P0, full context in that
        // method's own javadoc). Downgraded to UNKNOWN here for the same reason -- this
        // codebase's own AutoTradeService already raises a CRITICAL incident and halts on an
        // UNKNOWN broker result rather than assuming a fill happened, so routing a genuinely
        // untracked PAPER order into that same existing safety path is the correct fix, not a
        // new one. A real cause of a genuine "never recorded" lookup (an OCO leg's own id,
        // handled through getOcoStatus instead, or an order placed against a since-restarted,
        // different adapter instance) should surface as exactly that -- unknown -- not be
        // quietly reported as a successful fill.
        return simulatedOrderStatusByBrokerOrderId.getOrDefault(brokerOrderId, new OrderStatusInfo("UNKNOWN", null, null, "{}"));
    }

    @Override
    public OrderStatusInfo getOrderStatusByClientOrderId(String apiKey, String apiSecret, BrokerMode mode, String symbol, String clientOrderId) {
        // P2-4 fix, same context as getOrderStatus's own updated javadoc immediately above.
        return simulatedOrderStatusByClientOrderId.getOrDefault(clientOrderId, new OrderStatusInfo("UNKNOWN", null, null, "{}"));
    }

    @Override
    public List<Fill> getFillsForOrder(String apiKey, String apiSecret, BrokerMode mode, String symbol, String orderId) {
        // Review finding, same context as this class's own class javadoc: no real fill data
        // exists for a simulated order -- this codebase's own callers (FillLedgerService) only
        // need SOME record to exist for their own accounting; a real quantity/price at zero
        // simulated commission is honest about what this represents, not fabricated precision.
        // Deliberately conservative: an empty list here (rather than a fabricated Fill) since
        // this class has no record of the ORIGINAL order's own price/quantity to reconstruct
        // one from at this call site -- callers already treat a fill-ledger gap as non-fatal.
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
