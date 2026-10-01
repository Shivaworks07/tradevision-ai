package com.tradevision.service.broker.dto;

/** Real permissions read back from the broker for a given key — never trust what the user typed. */
public record AccountPermissions(boolean canTrade, boolean canWithdraw, boolean canDeposit) {}
