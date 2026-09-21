package com.fdmultimedia.api.publishing.tiktok;
public class TikTokApiException extends RuntimeException { private final String code; private final boolean retryable,outcomeUnknown;
 public TikTokApiException(String c,String m,boolean r){this(c,m,r,false);} public TikTokApiException(String c,String m,boolean r,boolean u){super(m);code=c;retryable=r;outcomeUnknown=u;}
 public String code(){return code;} public boolean retryable(){return retryable;} public boolean outcomeUnknown(){return outcomeUnknown;}}
