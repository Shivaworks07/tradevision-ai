package com.tradevision.dto;

import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerMode;
import com.tradevision.model.BrokerType;

import java.time.LocalDateTime;

public record BrokerCredentialResponse(
    String id,
    BrokerType broker,
    BrokerMode mode,
    String keyHint,
    LocalDateTime connectedAt,
    LocalDateTime lastValidatedAt
) {
    public static BrokerCredentialResponse from(BrokerCredential c) {
        return new BrokerCredentialResponse(c.getId(), c.getBroker(), c.getMode(), c.getKeyHint(),
            c.getCreatedAt(), c.getLastValidatedAt());
    }
}
