package com.tradevision.model;

/** Supported broker integrations. Only BINANCE has a working adapter today;
 *  MUDREX is reserved for a future BrokerAdapter implementation. */
public enum BrokerType {
    BINANCE,
    MUDREX
}
