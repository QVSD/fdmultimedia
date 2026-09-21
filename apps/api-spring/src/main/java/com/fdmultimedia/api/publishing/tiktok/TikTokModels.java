package com.fdmultimedia.api.publishing.tiktok;
import java.time.Instant; import java.util.List;
public final class TikTokModels { private TikTokModels(){}
 public record Token(String openId,String accessToken,String refreshToken,Instant accessExpiresAt,Instant refreshExpiresAt,String scopes){}
 public record CreatorInfo(String username,String nickname,List<String> privacyLevelOptions,boolean commentDisabled,boolean duetDisabled,boolean stitchDisabled,int maxVideoPostDurationSec){}
 public record Init(String publishId,String uploadUrl){}
 public record Status(String status,String publicPostId,String failReason){}
}
