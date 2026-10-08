package com.tradevision.service.broker.dto;

/**
 * Key-level permissions from `GET /sapi/v1/account/apiRestrictions` -- what this specific API key
 * is permitted to do, as distinct from account-level flags like canWithdraw, which reflect
 * whether the account can withdraw at all regardless of this key's own restrictions and are
 * therefore not a reliable signal for what this key itself is allowed to do.
 *
 * Every boolean here defaults to the unsafe reading (as if the restriction were absent/disabled)
 * when the broker's response is missing a field, matching AccountPermissions' own defensive-
 * default convention — never assume a missing field means "safe."
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
