package com.tradevision.dto;

import lombok.Data;
import java.util.Set;

@Data
public class UserDto {
    private String id;
    private String firstName;
    private String lastName;
    private String email;
    private String mobile;
    private String tradingPlatform;
    private Set<String> favoriteStocks;
    private Set<String> favoriteCryptos;
    private Set<String> favoriteForex;
    // URL that trade/price alerts are posted to; surfaced here so the frontend can show/edit the
    // account's current value.
    private String alertWebhookUrl;
}
