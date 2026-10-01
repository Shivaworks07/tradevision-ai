package com.tradevision.service.broker.dto;

/**
 * Review finding (P1 #10 — "Withdrawal-permission check relies on /api/v3/account.canWithdraw"):
 * that account-level flag reflects whether the ACCOUNT can withdraw at all, not what THIS API KEY
 * is restricted to — a real account almost always reports canWithdraw=true regardless of the key's
 * own permissions, which would make the existing LIVE withdrawal check either always refuse (if
 * ever tightened) or be silently meaningless (as it was found to be). The key-level truth lives at
 * a separate endpoint, `GET /sapi/v1/account/apiRestrictions`, which reports exactly what THIS key
 * — not the account — is permitted to do. This record is that response's relevant fields.
 *
 * Every boolean here defaults to the UNSAFE reading (as if the restriction were absent/disabled)
 * when the broker's response is missing a field, mirroring AccountPermissions' own existing
 * defensive-default convention — never assume a missing field means "safe."
 */
public record ApiKeyRestrictions(
    /** True only if this key is IP-whitelisted on Binance. Required — an unrestricted key is a
     *  bigger blast radius if it ever leaks, regardless of what its trade/withdraw flags say. */
    boolean ipRestrict,
    /** Key-level withdrawal permission. Must be false. */
    boolean enableWithdrawals,
    /** Key-level permission to move funds to another Binance user's account. Must be false —
     *  functionally equivalent to a withdrawal for this application's threat model. */
    boolean enableInternalTransfer,
    /** Key-level permission to transfer between this account's own wallets (spot/margin/futures).
     *  Must be false — not needed for spot-only trading, and another way funds can leave the
     *  wallet this application actually manages. */
    boolean permitsUniversalTransfer,
    /** Key-level spot & margin trading permission. Must be true — without it, this key cannot
     *  actually place the spot orders this application exists to place. */
    boolean enableSpotAndMarginTrading
) {}
