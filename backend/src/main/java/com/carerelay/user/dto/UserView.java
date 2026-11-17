package com.carerelay.user.dto;

import com.carerelay.user.AppUser;

public record UserView(Long id, Long orgId, String nickname, String phone, String role) {

    public static UserView of(AppUser user) {
        return new UserView(user.getId(), null, user.getNickname(), user.getPhone(), user.getRole());
    }
}
