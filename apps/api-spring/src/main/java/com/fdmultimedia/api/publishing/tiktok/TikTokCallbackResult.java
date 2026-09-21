package com.fdmultimedia.api.publishing.tiktok;
public record TikTokCallbackResult(boolean success,String failureReason){static TikTokCallbackResult ok(){return new TikTokCallbackResult(true,null);}static TikTokCallbackResult fail(String r){return new TikTokCallbackResult(false,r);}}
