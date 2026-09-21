package com.fdmultimedia.api.publishing.tiktok;
import com.fdmultimedia.api.publishing.Publication; import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="tiktok_publication_settings") public class TikTokPublicationSettings {
 @Id @Column(name="publication_id") private UUID publicationId;
 @OneToOne(fetch=FetchType.LAZY,optional=false) @MapsId @JoinColumn(name="publication_id") private Publication publication;
 @Column(name="privacy_level",nullable=false) private String privacyLevel;
 @Column(name="disable_comment",nullable=false) private boolean disableComment;
 @Column(name="disable_duet",nullable=false) private boolean disableDuet;
 @Column(name="disable_stitch",nullable=false) private boolean disableStitch;
 @Column(name="is_aigc",nullable=false) private boolean aigc;
 @Column(name="brand_organic_toggle",nullable=false) private boolean brandOrganic;
 @Column(name="created_at",nullable=false) private Instant createdAt;
 protected TikTokPublicationSettings(){} public TikTokPublicationSettings(Publication p,TikTokSettingsRequest r,Instant n){publication=p;privacyLevel=r.privacyLevel();disableComment=r.disableComment();disableDuet=r.disableDuet();disableStitch=r.disableStitch();createdAt=n;}
 public String getPrivacyLevel(){return privacyLevel;} public boolean isDisableComment(){return disableComment;} public boolean isDisableDuet(){return disableDuet;} public boolean isDisableStitch(){return disableStitch;} public boolean isAigc(){return aigc;} public boolean isBrandOrganic(){return brandOrganic;}}
