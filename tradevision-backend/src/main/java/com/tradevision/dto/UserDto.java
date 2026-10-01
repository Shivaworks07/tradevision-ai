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
    // P2-14 fix ("WebhookAlertService/User.alertWebhookUrl: no endpoint sets the webhook, feature
    // is dead" -- external review, full context in AuthService's own webhookAlertService field
    // javadoc): surfaced here so the frontend can actually show/edit the account's current value.
    private String alertWebhookUrl;
}
