package com.carerelay.user.dto;

public record AuthResponse(String token, long expiresInHours, UserView user) {
}
