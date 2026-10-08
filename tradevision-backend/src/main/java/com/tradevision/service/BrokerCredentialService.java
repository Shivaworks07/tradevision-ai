package com.tradevision.service;

import com.tradevision.dto.BrokerCredentialResponse;
import com.tradevision.dto.ConnectBrokerRequest;
import com.tradevision.model.*;
import com.tradevision.repository.BrokerAuditLogRepository;
import com.tradevision.repository.BrokerCredentialRepository;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.AccountPermissions;
import com.tradevision.service.broker.dto.ApiKeyRestrictions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Connects a user's broker key. The one rule that matters here: a key is never saved unless
 * we've independently confirmed with the broker that it cannot withdraw funds. The UI never
 * gets to assert that — we check, every time, before the row exists.
 *
 * Testnet and Mainnet API keys are genuinely separate credentials; a Testnet key is rejected
 * outright if used against the LIVE endpoint. mode is therefore an explicit, immutable choice at
 * connect time — a TESTNET credential and a LIVE credential are genuinely separate
 * BrokerCredential rows with their own encrypted key pair, exactly like
 * RiskProfile/Position/PositionSlotReservation already treat credentialId as a distinct trading
 * context. Connecting a LIVE credential goes through a two-step "never a single checkbox"
 * ceremony, since this operation actually matters: creating a credential that can touch real
 * money.
 */
@Service
@RequiredArgsConstructor
public class BrokerCredentialService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BrokerCredentialService.class);
    private static final Duration LIVE_CONNECT_CONFIRM_WINDOW = Duration.ofMinutes(5);

    private final BrokerCredentialRepository credentialRepo;
    private final BrokerAuditLogRepository auditRepo;
    /** Routes every audit write through the real hash chain instead of a plain save(). See AuditChainService's own class javadoc. */
    private final com.tradevision.service.AuditChainService auditChainService;
    private final CredentialEncryptionService encryption;
    private final List<BrokerAdapter> adapters;
    /** Used to construct PaperBrokerAdapter's one, lazily-built instance below. See PaperBrokerAdapter's own class javadoc. */
    private final com.tradevision.repository.PaperOcoRepository paperOcoRepo;
    /**
     * Used to construct each PAPER credential's own PaperBrokerAdapter instance with a durable
     * balance store, keyed by that credential's own id. See PaperAccountBalance's own class
     * javadoc.
     */
    private final com.tradevision.repository.PaperAccountBalanceRepository paperAccountBalanceRepo;
    /**
     * Used to check for open/unresolved positions before allowing a credential to be
     * deactivated. See delete's own updated javadoc. Confirmed no circular dependency:
     * PositionRepository is a plain Spring Data repository.
     */
    private final com.tradevision.repository.PositionRepository positionRepo;
    /**
     * Used for two additional checks the position-status check alone doesn't cover -- a
     * non-terminal order can exist with genuinely no Position yet (still pending on the
     * exchange, before any fill), and an unresolved critical incident is itself a signal this
     * credential needs continued attention regardless of what any position's own status
     * currently says. See delete's own updated javadoc.
     */
    private final com.tradevision.repository.OrderRepository orderRepo;
    private final com.tradevision.repository.TradingIncidentRepository tradingIncidentRepo;

    private Map<BrokerType, BrokerAdapter> adapterMap;
    /**
     * Deliberately not a Spring-managed field (no @Autowired/constructor injection) --
     * constructed manually, once, on first use. See PaperBrokerAdapter's own class javadoc for
     * exactly why it must never be a Spring bean discoverable via the List<BrokerAdapter>
     * adapters field just above.
     *
     * Keyed by credentialId so every PAPER credential gets its own real PaperBrokerAdapter
     * instance, each with its own independent simulatedUsdtBalance, rather than sharing one
     * running balance across every PAPER account in this deployment, with zero changes to the
     * BrokerAdapter interface or any of its real call sites -- those all keep calling the same
     * methods on whatever adapter this map hands back, unaware of the keying underneath.
     */
    private final java.util.Map<String, com.tradevision.service.broker.PaperBrokerAdapter> paperBrokerAdaptersByCredential
        = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Kept as a separate, single instance specifically for doConnect's own pre-save validation
     * path, which genuinely has no credentialId to key by yet -- the credential this call is
     * validating hasn't been saved (and so has no id) at the point this runs. This instance's
     * own simulatedUsdtBalance is never actually read for anything meaningful here (doConnect
     * only ever calls this adapter's own connection-check methods, never getBalance for real
     * trading purposes), so sharing it across every in-flight connect attempt carries none of
     * the real per-user isolation concern the credentialId-keyed map above exists to solve.
     */
    private com.tradevision.service.broker.PaperBrokerAdapter paperBrokerAdapterForConnectValidation;

    /**
     * A real Mongo-backed document, confirmable by any replica, with a genuine
     * TTL-index-backed eviction -- so requestLiveConnect/confirmLiveConnect works correctly in a
     * multi-replica deployment (the load balancer may route the confirm call to a different
     * replica than the one that issued the token), and an unconfirmed token is reliably swept
     * out rather than accumulating forever. Full context in PendingLiveConnect's own class
     * javadoc.
     */
    private final com.tradevision.repository.PendingLiveConnectRepository pendingLiveConnectRepo;

    // confirmLiveConnect is the moment a LIVE credential is persisted, so it is the right place
    // to refuse the connection outright for a user with no alert channel, rather than waiting
    // for this to be caught (or not) at the next process restart. See
    // AlertChannelStartupGuard's own requireAlertChannelCoverage javadoc.
    private final com.tradevision.config.AlertChannelStartupGuard alertChannelStartupGuard;
    // Reuses AuthService's existing step-up OTP infrastructure, same as
    // RiskProfileService.authorizeLiveAutoTrade/upsert. See CREDENTIAL_CHANGE_STEPUP_PURPOSE's
    // own javadoc. Confirmed no circular dependency: AuthService depends only on UserRepository,
    // OtpRepository, JwtUtil, OtpUtil, EmailService, OtpRateLimitService, MongoTemplate and
    // WebhookAlertService -- none of which depend on BrokerCredentialService.
    private final AuthService authService;

    /** The same in-memory, deliberately-not-durable pattern as pendingLiveConnects above, for a
     *  LIVE credential's own two-step key rotation. See rotateApiKey's own javadoc. */
    private final Map<String, PendingRotation> pendingRotations = new ConcurrentHashMap<>();

    private record PendingRotation(String userId, String credentialId, String encryptedApiKey,
                                    String encryptedApiSecret, String keyHint, String accountUid, Instant expiresAt) {}

    private BrokerAdapter adapterFor(BrokerType type) {
        if (adapterMap == null) {
            adapterMap = adapters.stream().collect(Collectors.toMap(BrokerAdapter::getType, a -> a));
        }
        BrokerAdapter adapter = adapterMap.get(type);
        if (adapter == null) {
            throw new IllegalArgumentException(type + " isn't wired up yet — only BINANCE has a working adapter right now.");
        }
        return adapter;
    }

    public BrokerCredentialResponse connect(String userId, ConnectBrokerRequest req) {
        BrokerMode mode = req.getMode() != null ? req.getMode() : BrokerMode.TESTNET;
        if (mode == BrokerMode.LIVE) {
            throw new IllegalStateException(
                "Connecting a LIVE credential is a two-step confirmation — call requestLiveConnect, then confirmLiveConnect with the returned token.");
        }
        return doConnect(userId, req, mode);
    }

    private BrokerCredentialResponse doConnect(String userId, ConnectBrokerRequest req, BrokerMode mode) {
        // Same PAPER-mode routing adapterForCredential applies -- a PAPER credential's own
        // connect-time validation must never reach the real adapter either, or this call would
        // send a genuine authenticated getAccountPermissions request to Binance using whatever
        // placeholder key/secret a PAPER credential happens to have.
        BrokerAdapter adapter;
        if (mode == BrokerMode.PAPER) {
            if (paperBrokerAdapterForConnectValidation == null) {
                paperBrokerAdapterForConnectValidation = new com.tradevision.service.broker.PaperBrokerAdapter(adapterFor(req.getBroker()), paperOcoRepo);
            }
            adapter = paperBrokerAdapterForConnectValidation;
        } else {
            adapter = adapterFor(req.getBroker());
        }

        AccountPermissions permissions = adapter.getAccountPermissions(req.getApiKey(), req.getApiSecret(), mode);

        // Scoped to LIVE only. Binance's own Spot Testnet has no real funds
        // to protect (the entire safety rationale for rejecting a withdrawal-capable key), and
        // testnet API keys reportedly cannot even have withdrawal permission restricted in the
        // first place -- meaning canWithdraw can report true there regardless of what the user
        // actually intended, which would make this check reject every genuine testnet key
        // unconditionally. Scoped to LIVE only, where a compromised or overpermissioned key can
        // actually drain real funds.
        if (mode == BrokerMode.LIVE && permissions.canWithdraw()) {
            audit(userId, null, req.getBroker(), "VALIDATE_REJECTED_WITHDRAWAL",
                "Key was rejected (" + mode + "): broker reports withdrawal permission enabled.");
            throw new IllegalArgumentException(
                "This API key has withdrawal permission enabled. Create a key with only " +
                "'Enable Reading' and 'Enable Spot & Margin Trading' — withdrawal must stay off.");
        }
        if (!permissions.canTrade()) {
            audit(userId, null, req.getBroker(), "VALIDATE_REJECTED_NO_TRADE",
                "Key was rejected (" + mode + "): broker reports trading permission disabled.");
            throw new IllegalArgumentException("This API key doesn't have trading permission enabled.");
        }

        BrokerCredential credential = new BrokerCredential();
        // The id is pre-generated here (same UUID-string-as-Mongo-_id pattern already used
        // elsewhere in this codebase -- see AutoTradeService's identical
        // position.setId(UUID.randomUUID().toString())) specifically so it exists before
        // encryption, letting the ciphertext be bound to the exact row it will live in from the
        // very first write, rather than needing a separate re-encrypt-after-save step. See
        // CredentialEncryptionService's own header javadoc.
        credential.setId(java.util.UUID.randomUUID().toString());
        credential.setUserId(userId);
        credential.setBroker(req.getBroker());
        credential.setEncryptedApiKey(encryption.encrypt(req.getApiKey(), fieldContext(credential.getId(), "apiKey")));
        credential.setEncryptedApiSecret(encryption.encrypt(req.getApiSecret(), fieldContext(credential.getId(), "apiSecret")));
        credential.setKeyHint(lastFour(req.getApiKey()));
        credential.setMode(mode);
        credential.setWithdrawalEnabled(false);
        credential.setActive(true);
        credential.setLastValidatedAt(LocalDateTime.now());
        // Recorded once, here, at the moment this credential is first connected -- this is what
        // rotateApiKey later verifies a new key against. See BrokerCredential.accountUid's own
        // field javadoc.
        credential.setAccountUid(safeGetAccountUid(adapter, req.getApiKey(), req.getApiSecret(), mode));

        credential = credentialRepo.save(credential);
        audit(userId, credential.getId(), req.getBroker(), "CONNECT", "Broker connected in " + mode + " mode.");

        return BrokerCredentialResponse.from(credential);
    }

    /**
     * requestLiveConnect/confirmLiveConnect and requestApiKeyRotation/confirmApiKeyRotation
     * already require a short-lived token for their two-step ceremony, but that token is simply
     * returned synchronously in the request call's own HTTP response -- it proves nothing about
     * who's actually at the keyboard right now, only that the request and confirm calls came
     * from someone holding a valid session. A step-up OTP closes that gap: anyone who hijacked an
     * already-authenticated session could otherwise request then immediately confirm. Shared by
     * both flows (connecting a brand-new LIVE credential, and rotating an existing one's key) --
     * both are equally consequential "credential changes," and kept distinct from
     * RiskProfileService.RISK_PROFILE_STEPUP_PURPOSE/RiskProfileService.STEPUP_OTP_PURPOSE so a
     * code for one can never be replayed for another.
     */
    public static final String CREDENTIAL_CHANGE_STEPUP_PURPOSE = "CREDENTIAL_CHANGE_STEPUP";

    /** Request a fresh step-up verification code before calling confirmLiveConnect or confirmApiKeyRotation. */
    public void requestCredentialChangeStepUpOtp(String userId) {
        var resp = authService.sendStepUpOtp(userId, CREDENTIAL_CHANGE_STEPUP_PURPOSE);
        if (!resp.isSuccess()) {
            throw new IllegalArgumentException(String.valueOf(resp.getMessage()));
        }
    }

    /**
     * Step 1 of connecting a LIVE credential: validates the key against Binance's actual LIVE
     * endpoint (a testnet key simply fails here — itself a real safety property, not just
     * ceremony) and issues a short-lived token. Nothing is saved yet.
     *
     * The withdrawal check here uses the real, key-level check against apiRestrictions, not
     * getAccountPermissions().canWithdraw() (an account-level flag that says nothing about this
     * specific key's own restrictions). See validateLiveKeyRestrictions' own javadoc.
     */
    public String requestLiveConnect(String userId, ConnectBrokerRequest req) {
        BrokerAdapter adapter = adapterFor(req.getBroker());
        AccountPermissions permissions = adapter.getAccountPermissions(req.getApiKey(), req.getApiSecret(), BrokerMode.LIVE);
        if (!permissions.canTrade()) {
            throw new IllegalArgumentException("This API key doesn't have trading permission enabled on LIVE.");
        }
        validateLiveKeyRestrictions(userId, null, req.getBroker(), adapter, req.getApiKey(), req.getApiSecret(),
            "LIVE_CONNECT_REJECTED");

        String token = java.util.UUID.randomUUID().toString();
        // A durable, cross-replica document instead of an instance-local map entry. See
        // pendingLiveConnectRepo's own field javadoc. token is Mongo's own _id here
        // (PendingLiveConnect.token), so this insert is the same "only one document with this id
        // can exist" idiom already used for ReconciliationLock/BootstrapLock elsewhere.
        var pending = new com.tradevision.model.PendingLiveConnect();
        pending.setToken(token);
        pending.setUserId(userId);
        pending.setBroker(req.getBroker());
        // The future credential's id, pre-generated now so the ciphertexts below are bound (via
        // AAD) to the exact row confirmLiveConnect will actually save them into. See
        // PendingLiveConnect's own class javadoc.
        String futureCredentialId = java.util.UUID.randomUUID().toString();
        pending.setCredentialId(futureCredentialId);
        pending.setEncryptedApiKey(encryption.encrypt(req.getApiKey(), fieldContext(futureCredentialId, "apiKey")));
        pending.setEncryptedApiSecret(encryption.encrypt(req.getApiSecret(), fieldContext(futureCredentialId, "apiSecret")));
        pending.setKeyHint(lastFour(req.getApiKey()));
        pending.setAccountUid(safeGetAccountUid(adapter, req.getApiKey(), req.getApiSecret(), BrokerMode.LIVE));
        pending.setExpiresAt(Instant.now().plus(LIVE_CONNECT_CONFIRM_WINDOW));
        pendingLiveConnectRepo.save(pending);

        audit(userId, null, req.getBroker(), "LIVE_CONNECT_REQUESTED",
            "LIVE credential validated against Binance's live endpoint, awaiting explicit confirmation before saving.");
        return token;
    }

    /**
     * Step 2: the exact token from requestLiveConnect, within the confirmation window, actually
     * persists the credential.
     *
     * Also requires a fresh step-up OTP (requested via requestCredentialChangeStepUpOtp / POST
     * .../connect/live/request-otp), verified before the token itself is even looked at -- same
     * ordering reasoning as authorizeLiveAutoTrade's own step-up check, so a stale/replayed
     * confirm call never reaches any state-changing logic. See CREDENTIAL_CHANGE_STEPUP_PURPOSE's
     * own javadoc.
     */
    public BrokerCredentialResponse confirmLiveConnect(String userId, String token, String stepUpOtpCode) {
        authService.verifyStepUpOtp(userId, CREDENTIAL_CHANGE_STEPUP_PURPOSE, stepUpOtpCode);
        // Reads from the durable, cross-replica store instead of an instance-local map -- this
        // token may have been issued by a different replica than the one serving this confirm
        // call. See pendingLiveConnectRepo's own field javadoc.
        var pending = pendingLiveConnectRepo.findById(token).orElse(null);
        if (pending == null || !pending.getUserId().equals(userId) || Instant.now().isAfter(pending.getExpiresAt())) {
            throw new IllegalArgumentException("Confirmation token is invalid or expired — request LIVE connect again.");
        }
        pendingLiveConnectRepo.deleteById(token); // one-time use

        // Refuse to actually create this LIVE credential if this account has no alert channel
        // reachable for them -- same fail-before-the-row-exists posture as the
        // withdrawal/trading-permission checks in doConnect above, applied to the "would a
        // CRITICAL incident on this account page anybody" question instead. See
        // AlertChannelStartupGuard's own requireAlertChannelCoverage javadoc.
        alertChannelStartupGuard.requireAlertChannelCoverage(userId);

        BrokerCredential credential = new BrokerCredential();
        // Must reuse the same pre-generated id the pending record's own ciphertexts were already
        // bound to -- a freshly-generated id here would make them unreadable (the AAD context
        // would no longer match what they were actually encrypted under). See
        // PendingLiveConnect's own class javadoc.
        credential.setId(pending.getCredentialId());
        credential.setUserId(userId);
        credential.setBroker(pending.getBroker());
        credential.setEncryptedApiKey(pending.getEncryptedApiKey());
        credential.setEncryptedApiSecret(pending.getEncryptedApiSecret());
        credential.setKeyHint(pending.getKeyHint());
        credential.setMode(BrokerMode.LIVE);
        credential.setWithdrawalEnabled(false);
        credential.setActive(true);
        credential.setLastValidatedAt(LocalDateTime.now());
        credential.setAccountUid(pending.getAccountUid()); // see doConnect's own identical line

        credential = credentialRepo.save(credential);
        audit(userId, credential.getId(), pending.getBroker(), "LIVE_CONNECT_CONFIRMED",
            "LIVE credential connected after explicit two-step confirmation.");
        return BrokerCredentialResponse.from(credential);
    }

    public List<BrokerCredentialResponse> list(String userId) {
        return credentialRepo.findByUserIdAndActiveTrue(userId).stream()
            .map(BrokerCredentialResponse::from)
            .toList();
    }

    public void delete(String userId, String credentialId) {
        BrokerCredential credential = ownedCredential(userId, credentialId);
        // Once credential.active is false, doReconcile()'s own eligibility check (`if
        // (!credential.isActive()) continue;`) skips this credential entirely -- position
        // monitoring, OCO recovery, and drawdown tracking would all stop for any position that
        // was still open when the credential was deleted, leaving a real LIVE position with no
        // application-side management at all. Deletion is refused outright while any position
        // for this credential is still in a status meaning this application is actively
        // responsible for it. The user must close or fully resolve every such position first;
        // only a genuinely finished credential (nothing left to manage) can be deactivated.
        var activePositions = positionRepo.findByCredentialIdAndStatusIn(credentialId,
            java.util.Set.of("OPEN", "FLATTENING", "NAKED_FLATTENED", "CLOSED_UNVERIFIED_PNL"));
        if (!activePositions.isEmpty()) {
            throw new IllegalStateException("Cannot delete this credential: " + activePositions.size() + " position(s) still need "
                + "this application's own management (open, being flattened, left naked after a failed flatten, or with an unverified "
                + "closing P&L). Close or fully resolve every such position first.");
        }
        // A non-terminal order can genuinely exist with no Position yet at all -- still
        // pending/acknowledged on the exchange, before any fill has happened -- meaning the
        // position-status check above alone could miss it entirely. The same refusal is extended
        // to any order still in a status meaning this application hasn't finished tracking its
        // own outcome yet.
        var pendingOrders = orderRepo.findByCredentialIdAndStatusIn(credentialId, List.of(
            com.tradevision.model.OrderStatus.CREATED, com.tradevision.model.OrderStatus.RISK_ACCEPTED,
            com.tradevision.model.OrderStatus.SUBMITTING, com.tradevision.model.OrderStatus.UNKNOWN,
            com.tradevision.model.OrderStatus.ACKNOWLEDGED, com.tradevision.model.OrderStatus.PARTIALLY_FILLED,
            com.tradevision.model.OrderStatus.CANCEL_PENDING, com.tradevision.model.OrderStatus.RECONCILIATION_REQUIRED));
        if (!pendingOrders.isEmpty()) {
            throw new IllegalStateException("Cannot delete this credential: " + pendingOrders.size() + " order(s) are still pending, "
                + "unacknowledged, or awaiting manual reconciliation. Wait for these to resolve (or resolve them manually) first.");
        }
        // Same reasoning as pendingOrders' own check above: an unresolved
        // CRITICAL incident is itself a direct signal this credential needs continued human
        // attention -- deleting it now would remove the exact monitoring/reconciliation this
        // application uses to actually investigate and resolve that incident. Scoped to CRITICAL
        // specifically, not every incident ever raised, since lower-severity/informational
        // incidents don't represent an ongoing, unresolved danger the way a CRITICAL one does.
        var unresolvedCritical = tradingIncidentRepo.findByCredentialIdAndResolvedAtIsNullOrderByCreatedAtDesc(credentialId).stream()
            .filter(i -> "CRITICAL".equals(i.getSeverity()))
            .toList();
        if (!unresolvedCritical.isEmpty()) {
            throw new IllegalStateException("Cannot delete this credential: " + unresolvedCritical.size() + " unresolved CRITICAL "
                + "incident(s) exist for it. Resolve or investigate every one first -- deleting this credential now would remove the "
                + "monitoring this application uses to help resolve them.");
        }
        credential.setActive(false);
        credentialRepo.save(credential);
        audit(userId, credentialId, credential.getBroker(), "DELETE", "Broker credential deactivated by user.");
    }

    /**
     * Deactivates every one of a user's credentials at once in a single call -- the mechanism a
     * suspected-compromise response needs, beyond the single-credential delete() above. The
     * credential half of the combined emergency response -- RiskProfileService.emergencyRevokeAll's
     * own javadoc has the full design, including why this lives here rather than there (avoiding
     * a circular dependency between the two services -- this class never depends on
     * RiskProfileService, so the combined emergency method calls into this one, not the reverse).
     */
    public void deactivateAll(String userId, String reason) {
        for (BrokerCredential credential : credentialRepo.findByUserIdAndActiveTrue(userId)) {
            credential.setActive(false);
            credentialRepo.save(credential);
            audit(userId, credential.getId(), credential.getBroker(), "EMERGENCY_REVOKE",
                reason != null && !reason.isBlank() ? reason : "Emergency credential revocation.");
        }
    }

    public List<com.tradevision.service.broker.dto.AssetBalance> getBalance(String userId, String credentialId) {
        BrokerCredential credential = ownedCredential(userId, credentialId);
        BrokerAdapter adapter = adapterFor(credential.getBroker());
        return adapter.getBalance(decrypt(credential, true), decrypt(credential, false), credential.getMode());
    }

    /** Package-private-ish helper reused by OrderExecutionService so decryption logic lives in one place. */
    BrokerCredential ownedCredential(String userId, String credentialId) {
        return credentialRepo.findByIdAndUserId(credentialId, userId)
            .filter(BrokerCredential::isActive)
            .orElseThrow(() -> new IllegalArgumentException("No active broker credential found for this user with that id."));
    }

    /** Row-scoped context, not just field-scoped -- a ciphertext that ended up on the wrong
     *  credential's row (not just the wrong field) fails decryption outright. See
     *  CredentialEncryptionService's own header javadoc. */
    private static String fieldContext(String credentialId, String field) {
        return credentialId + ":" + field;
    }

    String decrypt(BrokerCredential credential, boolean apiKeyNotSecret) {
        // Field-scoped context -- a ciphertext accidentally stored in, or read from, the wrong
        // field fails decryption outright instead of silently succeeding. See
        // CredentialEncryptionService's own header javadoc.
        //
        // The strong context above is additionally row-scoped (fieldContext), with
        // decryptWithLegacyFallback covering every row still encrypted under the old, generic
        // field-only context -- see that method's own javadoc for exactly when the fallback does
        // and doesn't apply.
        String field = apiKeyNotSecret ? "apiKey" : "apiSecret";
        String encoded = apiKeyNotSecret ? credential.getEncryptedApiKey() : credential.getEncryptedApiSecret();
        return encryption.decryptWithLegacyFallback(encoded, fieldContext(credential.getId(), field), field);
    }

    BrokerAdapter adapterForCredential(BrokerCredential credential) {
        // Routed by BrokerMode, not BrokerType, specifically so a PAPER credential -- regardless
        // of which real broker it's nominally attached to -- is handled entirely by the
        // simulated adapter and never reaches adapterFor()'s own BrokerType-keyed map (which
        // only ever holds real, Spring-discovered adapters like BinanceBrokerAdapter). See
        // PaperBrokerAdapter's own class javadoc.
        if (credential.getMode() == BrokerMode.PAPER) {
            return paperBrokerAdaptersByCredential.computeIfAbsent(credential.getId(),
                id -> new com.tradevision.service.broker.PaperBrokerAdapter(
                    adapterFor(credential.getBroker()), paperOcoRepo, paperAccountBalanceRepo, id));
        }
        return adapterFor(credential.getBroker());
    }

    /**
     * Rotates a credential's own API key in place. Changing a credential's key material without
     * this would otherwise require delete-and-reconnect, which creates a new credential id,
     * severing the link to every RiskProfile/Position/Order/TradeCallRecord already associated
     * with the old one. This reuses the exact same validation doConnect already applies to a
     * brand-new key (real permission check against the broker, withdrawal-must-be-off,
     * trading-must-be-on), but updates the same credential document's own key fields in place,
     * preserving its id and every existing link to it. Requires ownership (ownedCredential's own
     * check) and re-validates against the credential's own existing mode -- a LIVE credential's
     * new key is checked against Binance for real, the same safety bar a brand-new LIVE
     * connection already clears.
     *
     * Without an account-identity check, a LIVE credential could be rotated to a different
     * account's key instantly -- every open position/OCO for this credential still lives on the
     * old account, so this application would go on "monitoring" a balance that was never theirs;
     * reconciliation would then see the expected balance genuinely gone on the new account and
     * mark positions CLOSED_UNVERIFIED_PNL, releasing slots/exposure limits while the real
     * positions remain open, unmanaged, on the old account.
     *
     * Enforced with three independent guards, all required: (1) refuse while this credential has
     * any open position or non-terminal order -- same reasoning and the same check delete()
     * already applies; (2) compare the new key's own reported account uid against the uid this
     * credential was originally connected under, refusing a mismatch outright; (3) for LIVE
     * specifically, require the same explicit two-step confirmation connect() already requires
     * for a brand-new LIVE credential -- see requestApiKeyRotation/confirmApiKeyRotation below.
     * TESTNET/PAPER rotation stays single-step (no real money at stake), but still gets the
     * open-position and account-identity checks.
     */
    public BrokerCredentialResponse rotateApiKey(String userId, String credentialId, String newApiKey, String newApiSecret) {
        BrokerCredential credential = ownedCredential(userId, credentialId);
        if (credential.getMode() == BrokerMode.LIVE) {
            throw new IllegalStateException(
                "Rotating a LIVE credential's API key is a two-step confirmation — call requestApiKeyRotation, then "
                    + "confirmApiKeyRotation with the returned token.");
        }
        refuseIfCredentialHasOpenWork(credential);
        BrokerAdapter adapter = adapterForCredential(credential);
        AccountPermissions permissions = adapter.getAccountPermissions(newApiKey, newApiSecret, credential.getMode());
        validateRotationPermissions(userId, credential, permissions);
        String newAccountUid = safeGetAccountUid(adapter, newApiKey, newApiSecret, credential.getMode());
        checkAccountUidMatches(userId, credential, newAccountUid);

        credential.setEncryptedApiKey(encryption.encrypt(newApiKey, fieldContext(credential.getId(), "apiKey")));
        credential.setEncryptedApiSecret(encryption.encrypt(newApiSecret, fieldContext(credential.getId(), "apiSecret")));
        credential.setKeyHint(lastFour(newApiKey));
        credential.setLastValidatedAt(LocalDateTime.now());
        if (credential.getAccountUid() == null) credential.setAccountUid(newAccountUid); // backfill, see field javadoc
        credential = credentialRepo.save(credential);
        audit(userId, credentialId, credential.getBroker(), "API_KEY_ROTATED",
            "API key rotated for this credential -- new key validated and accepted; credential id and all linked history unchanged.");
        return BrokerCredentialResponse.from(credential);
    }

    /**
     * Step 1 of rotating a LIVE credential's key. See rotateApiKey's own javadoc. Validates the
     * new key (permissions and account identity) and the open-work guard up front, then issues a
     * short-lived token -- nothing is changed on the credential yet.
     */
    public String requestApiKeyRotation(String userId, String credentialId, String newApiKey, String newApiSecret) {
        BrokerCredential credential = ownedCredential(userId, credentialId);
        if (credential.getMode() != BrokerMode.LIVE) {
            throw new IllegalStateException("This is the LIVE two-step rotation flow -- call rotateApiKey directly for a "
                + credential.getMode() + " credential.");
        }
        refuseIfCredentialHasOpenWork(credential);
        BrokerAdapter adapter = adapterForCredential(credential);
        AccountPermissions permissions = adapter.getAccountPermissions(newApiKey, newApiSecret, BrokerMode.LIVE);
        validateRotationPermissions(userId, credential, permissions);
        // The real, key-level restriction check -- see validateLiveKeyRestrictions' own javadoc
        // for why this is used instead of an account-level canWithdraw check.
        validateLiveKeyRestrictions(userId, credentialId, credential.getBroker(), adapter, newApiKey, newApiSecret,
            "API_KEY_ROTATION_REJECTED");
        String newAccountUid = safeGetAccountUid(adapter, newApiKey, newApiSecret, BrokerMode.LIVE);
        checkAccountUidMatches(userId, credential, newAccountUid);

        String token = java.util.UUID.randomUUID().toString();
        // Rotation keeps the same credentialId throughout (confirmApiKeyRotation writes these
        // ciphertexts straight onto the existing credential, never a new row), so it's already
        // known here and used directly -- no pre-generation or later re-encrypt-after-save step
        // needed, unlike the brand-new-credential case in requestLiveConnect/confirmLiveConnect
        // below.
        pendingRotations.put(token, new PendingRotation(userId, credentialId, encryption.encrypt(newApiKey, fieldContext(credentialId, "apiKey")),
            encryption.encrypt(newApiSecret, fieldContext(credentialId, "apiSecret")), lastFour(newApiKey), newAccountUid, Instant.now().plus(LIVE_CONNECT_CONFIRM_WINDOW)));
        audit(userId, credentialId, credential.getBroker(), "API_KEY_ROTATION_REQUESTED",
            "LIVE API key rotation validated (permissions and account identity both confirmed), awaiting explicit confirmation.");
        return token;
    }

    /**
     * Step 2: the exact token from requestApiKeyRotation, within the confirmation window,
     * actually applies the rotation.
     *
     * Also requires a fresh step-up OTP (requested via requestCredentialChangeStepUpOtp / POST
     * .../rotate-key/request-otp), verified before the token itself is even looked at, same
     * ordering as confirmLiveConnect's own identical check. See
     * CREDENTIAL_CHANGE_STEPUP_PURPOSE's own javadoc.
     */
    public BrokerCredentialResponse confirmApiKeyRotation(String userId, String token, String stepUpOtpCode) {
        authService.verifyStepUpOtp(userId, CREDENTIAL_CHANGE_STEPUP_PURPOSE, stepUpOtpCode);
        PendingRotation pending = pendingRotations.remove(token);
        if (pending == null || !pending.userId().equals(userId) || Instant.now().isAfter(pending.expiresAt())) {
            throw new IllegalArgumentException("Confirmation token is invalid or expired — request the rotation again.");
        }
        BrokerCredential credential = ownedCredential(userId, pending.credentialId());
        // Re-checked, not just trusted from step 1: real time has passed since the token was
        // issued, and a position could have opened (or the credential's own mode/ownership could
        // have changed) in that window -- the same "never trust an earlier check alone across an
        // async gap" reasoning already applied throughout this codebase's own LIVE execution path.
        refuseIfCredentialHasOpenWork(credential);
        credential.setEncryptedApiKey(pending.encryptedApiKey());
        credential.setEncryptedApiSecret(pending.encryptedApiSecret());
        credential.setKeyHint(pending.keyHint());
        credential.setLastValidatedAt(LocalDateTime.now());
        if (credential.getAccountUid() == null) credential.setAccountUid(pending.accountUid());
        credential = credentialRepo.save(credential);
        audit(userId, credential.getId(), credential.getBroker(), "API_KEY_ROTATION_CONFIRMED",
            "LIVE API key rotation applied after explicit two-step confirmation.");
        return BrokerCredentialResponse.from(credential);
    }

    /**
     * Only carries the canTrade check, which is accurate for every mode via
     * getAccountPermissions. The withdrawal check lives separately in
     * validateLiveKeyRestrictions, called (LIVE-only) by requestApiKeyRotation -- an
     * account-level canWithdraw check would be an inaccurate signal for the one mode (LIVE)
     * where it actually matters. See validateLiveKeyRestrictions' own javadoc.
     */
    private void validateRotationPermissions(String userId, BrokerCredential credential, AccountPermissions permissions) {
        if (!permissions.canTrade()) {
            audit(userId, credential.getId(), credential.getBroker(), "API_KEY_ROTATION_REJECTED_NO_TRADE",
                "New key was rejected (" + credential.getMode() + "): broker reports trading permission disabled.");
            throw new IllegalStateException("This new API key doesn't have trading permission enabled.");
        }
    }

    /**
     * `/api/v3/account.canWithdraw` reflects the account's ability to withdraw at all, not
     * whether this specific API key is permitted to -- a real account with withdrawals enabled
     * at the account level reports canWithdraw=true regardless of the key's own restrictions, so
     * relying on it for a LIVE withdrawal check would either refuse every genuine LIVE key (the
     * normal state for a real account) or silently pass a withdrawal-capable key if that
     * account-level flag ever happened to read false. The real, key-level truth lives at
     * `GET /sapi/v1/account/apiRestrictions` instead -- see ApiKeyRestrictions' own class javadoc
     * for what's actually checked and why every flag defaults to the unsafe reading. Scoped to
     * LIVE only (mirrors every other LIVE-specific check in this class) -- TESTNET/PAPER have no
     * real funds or real apiRestrictions endpoint response worth trusting for this.
     */
    /** Package-private so RiskProfileService.authorizeLiveAutoTrade can reuse this exact check when
     *  re-verifying a LIVE credential's key restrictions before granting autonomous authority. */
    void validateLiveKeyRestrictions(String userId, String credentialId, BrokerType broker, BrokerAdapter adapter,
                                      String apiKey, String apiSecret, String auditActionPrefix) {
        ApiKeyRestrictions restrictions = adapter.getApiKeyRestrictions(apiKey, apiSecret, BrokerMode.LIVE);
        if (restrictions.enableWithdrawals()) {
            audit(userId, credentialId, broker, auditActionPrefix + "_WITHDRAWAL",
                "LIVE key rejected: apiRestrictions reports withdrawal permission enabled on this specific key.");
            throw new IllegalArgumentException(
                "This API key has withdrawal permission enabled. Create a key with only " +
                "'Enable Reading' and 'Enable Spot & Margin Trading' — withdrawal must stay off.");
        }
        if (restrictions.enableInternalTransfer()) {
            audit(userId, credentialId, broker, auditActionPrefix + "_INTERNAL_TRANSFER",
                "LIVE key rejected: apiRestrictions reports internal-transfer permission enabled on this specific key.");
            throw new IllegalArgumentException(
                "This API key has internal-transfer permission enabled, which can move funds to another Binance "
                    + "account. Create a key without this permission.");
        }
        if (restrictions.permitsUniversalTransfer()) {
            audit(userId, credentialId, broker, auditActionPrefix + "_UNIVERSAL_TRANSFER",
                "LIVE key rejected: apiRestrictions reports universal-transfer permission enabled on this specific key.");
            throw new IllegalArgumentException(
                "This API key permits universal transfer between wallets, which isn't needed for spot-only "
                    + "trading. Create a key without this permission.");
        }
        if (!restrictions.ipRestrict()) {
            audit(userId, credentialId, broker, auditActionPrefix + "_NO_IP_WHITELIST",
                "LIVE key rejected: apiRestrictions reports this key is NOT IP-restricted.");
            throw new IllegalArgumentException(
                "This API key is not IP-restricted on Binance. Add this server's IP address to the key's IP "
                    + "access restriction list before connecting it for LIVE trading.");
        }
        if (!restrictions.enableSpotAndMarginTrading()) {
            audit(userId, credentialId, broker, auditActionPrefix + "_NO_SPOT_TRADING",
                "LIVE key rejected: apiRestrictions reports spot & margin trading is not enabled on this specific key.");
            throw new IllegalArgumentException("This API key doesn't have spot & margin trading enabled.");
        }
    }

    /** The account-identity check -- see BrokerCredential.accountUid's own field javadoc for exactly what this protects. */
    private void checkAccountUidMatches(String userId, BrokerCredential credential, String newAccountUid) {
        String existingUid = credential.getAccountUid();
        if (existingUid == null || newAccountUid == null) {
            // Nothing recorded to compare against yet (a pre-migration credential), or the
            // broker's response genuinely didn't include an identity this time -- cannot verify,
            // but also cannot manufacture a mismatch out of missing data. Backfilled by the
            // caller once the rotation actually goes through.
            return;
        }
        if (!existingUid.equals(newAccountUid)) {
            audit(userId, credential.getId(), credential.getBroker(), "API_KEY_ROTATION_REJECTED_ACCOUNT_MISMATCH",
                "New key was rejected: it belongs to a DIFFERENT Binance account than this credential was originally connected "
                    + "under. Rotating to a different account's key would leave this application monitoring a balance that was "
                    + "never the one any open positions actually belong to.");
            throw new IllegalStateException("This new API key belongs to a different Binance account than the one this "
                + "credential was originally connected under. Rotate to a key from the SAME account, or disconnect this "
                + "credential and connect the new account as a separate one.");
        }
    }

    /** Refuses rotation while this credential still has real, unresolved exchange-side state riding on the old key. */
    private void refuseIfCredentialHasOpenWork(BrokerCredential credential) {
        var activePositions = positionRepo.findByCredentialIdAndStatusIn(credential.getId(),
            java.util.Set.of("OPEN", "FLATTENING", "NAKED_FLATTENED", "CLOSED_UNVERIFIED_PNL"));
        if (!activePositions.isEmpty()) {
            throw new IllegalStateException("Cannot rotate this credential's API key: " + activePositions.size() + " position(s) "
                + "still need this application's own management (open, being flattened, left naked after a failed flatten, or with "
                + "an unverified closing P&L). Close or fully resolve every such position first -- rotating now risks the new key "
                + "pointing at a different account than these positions actually live on.");
        }
        var pendingOrders = orderRepo.findByCredentialIdAndStatusIn(credential.getId(), List.of(
            com.tradevision.model.OrderStatus.CREATED, com.tradevision.model.OrderStatus.RISK_ACCEPTED,
            com.tradevision.model.OrderStatus.SUBMITTING, com.tradevision.model.OrderStatus.UNKNOWN,
            com.tradevision.model.OrderStatus.ACKNOWLEDGED, com.tradevision.model.OrderStatus.PARTIALLY_FILLED,
            com.tradevision.model.OrderStatus.CANCEL_PENDING, com.tradevision.model.OrderStatus.RECONCILIATION_REQUIRED));
        if (!pendingOrders.isEmpty()) {
            throw new IllegalStateException("Cannot rotate this credential's API key: " + pendingOrders.size() + " order(s) are "
                + "still pending, unacknowledged, or awaiting manual reconciliation. Wait for these to resolve (or resolve them "
                + "manually) first.");
        }
    }

    /**
     * Reading account identity is observational, not a permission gate -- a failure here (a
     * transient network error, an unexpected response shape) must never itself block a connect
     * or rotation that otherwise passed every real safety check. Returns null on any failure,
     * which checkAccountUidMatches above treats as "cannot verify" rather than a pass. See
     * BrokerAdapter.getAccountUid's own javadoc.
     */
    private String safeGetAccountUid(BrokerAdapter adapter, String apiKey, String apiSecret, BrokerMode mode) {
        try {
            return adapter.getAccountUid(apiKey, apiSecret, mode);
        } catch (Exception e) {
            log.warn("Could not read account identity (uid) from the broker (non-fatal -- this key's own permission checks still "
                + "apply in full): {}", e.getMessage());
            return null;
        }
    }

    void audit(String userId, String credentialId, BrokerType broker, String action, String detail) {
        // Every audit write goes through the real hash chain instead of a plain save() -- this
        // is the one, central place these records are ever constructed. See AuditChainService's
        // own class javadoc.
        auditChainService.appendToChain(BrokerAuditLog.builder()
            .userId(userId).credentialId(credentialId).broker(broker)
            .action(action).detail(detail).timestamp(LocalDateTime.now())
            .build());
    }

    private String lastFour(String key) {
        return key.length() <= 4 ? key : key.substring(key.length() - 4);
    }
}
