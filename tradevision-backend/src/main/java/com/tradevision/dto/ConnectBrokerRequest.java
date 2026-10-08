package com.tradevision.dto;

import com.tradevision.model.BrokerMode;
import com.tradevision.model.BrokerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request to connect a new broker credential. mode is explicit at connect time rather than
 * something flipped later, and defaults to TESTNET so the common case (trying out a testnet key)
 * needs no extra decision while LIVE must be chosen deliberately -- Binance testnet and mainnet
 * keys are genuinely different credentials, not the same key used against two hosts.
 */
@Data
public class ConnectBrokerRequest {
    @NotNull private BrokerType broker;
    @NotBlank private String apiKey;
    @NotBlank private String apiSecret;
    private BrokerMode mode = BrokerMode.TESTNET;
}
