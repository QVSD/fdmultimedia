package com.fdmultimedia.api.publishing.instagram;

import com.fdmultimedia.api.accounts.SocialAccountSummary;

public record InstagramCallbackResult(boolean success, SocialAccountSummary account, String failureReason) {

    public static InstagramCallbackResult success(SocialAccountSummary account) {
        return new InstagramCallbackResult(true, account, null);
    }

    public static InstagramCallbackResult failure(String reason) {
        return new InstagramCallbackResult(false, null, reason);
    }
}
