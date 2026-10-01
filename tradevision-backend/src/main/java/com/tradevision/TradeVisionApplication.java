package com.tradevision;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// Review item #10: @EnableAsync backs AutoTradeService's @Async evaluateSignal() method —
// moves Binance calls (which can take seconds, especially under retry/backoff) off the HTTP
// request thread handling POST /api/calls/save.
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class TradeVisionApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradeVisionApplication.class, args);
    }
}
