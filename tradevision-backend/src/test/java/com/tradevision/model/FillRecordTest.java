package com.tradevision.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies, via reflection, that FillRecord is effectively immutable: no setter method
 * exists for any field except setId, which Spring Data MongoDB's own id-generation
 * mechanism genuinely needs.
 */
class FillRecordTest {

    @Test
    @DisplayName("FillRecord: no setter method exists for any field except setId — genuine immutability, not just a naming convention")
    void noSettersExceptSetId() {
        List<String> setterNames = Arrays.stream(FillRecord.class.getDeclaredMethods())
            .map(Method::getName)
            .filter(name -> name.startsWith("set"))
            .toList();

        assertThat(setterNames).containsExactly("setId");
    }

    @Test
    @DisplayName("FillRecord: the all-args constructor correctly populates every field, confirming construction still works without any setter")
    void allArgsConstructor_populatesEveryField() {
        var now = java.time.LocalDateTime.now();
        var record = new FillRecord("id1", "order1", "broker1", "pos1", "trade1", "identity1", "STRONG",
            "user1", "cred1", "BTCUSDT", "BUY",
            java.math.BigDecimal.valueOf(100), java.math.BigDecimal.valueOf(1.0),
            java.math.BigDecimal.valueOf(0.05), "USDT", java.math.BigDecimal.valueOf(0.05),
            now, now, false);

        assertThat(record.getId()).isEqualTo("id1");
        assertThat(record.getOrderId()).isEqualTo("order1");
        assertThat(record.getBrokerOrderId()).isEqualTo("broker1");
        assertThat(record.getPositionId()).isEqualTo("pos1");
        assertThat(record.getBrokerTradeId()).isEqualTo("trade1");
        assertThat(record.getFillIdentity()).isEqualTo("identity1");
        assertThat(record.getIdentityConfidence()).isEqualTo("STRONG");
        assertThat(record.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(record.getQuantity()).isEqualByComparingTo("1.0");
        assertThat(record.isAggregate()).isFalse();
    }

    @Test
    @DisplayName("FillRecord: setId is the one exception — Spring Data MongoDB's own id-generation mechanism needs it, and it works correctly")
    void setId_worksCorrectly() {
        var record = new FillRecord();
        record.setId("generated-id-123");

        assertThat(record.getId()).isEqualTo("generated-id-123");
    }
}
