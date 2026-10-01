package com.tradevision.dto;

import com.tradevision.model.BrokerMode;
import com.tradevision.model.BrokerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Review finding ("P0 #1" — "LIVE Binance credential architecture is wrong"): mode is now
 * explicit at connect time, not something flipped after the fact. Defaults to TESTNET so the
 * common case (connecting a testnet key to try things out) needs no extra decision — LIVE must
 * be chosen deliberately. See BrokerCredentialService for why this matters: Binance testnet and
 * mainnet keys are genuinely different credentials, not the same key used against two hosts.
 */
@Data
public class ConnectBrokerRequest {
    @NotNull private BrokerType broker;
    @NotBlank private String apiKey;
    @NotBlank private String apiSecret;
    private BrokerMode mode = BrokerMode.TESTNET;
}
