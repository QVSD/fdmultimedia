package com.fdmultimedia.api.publishing.tiktok;
import com.fdmultimedia.api.publishing.Publication; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface TikTokPublicationSettingsRepository extends JpaRepository<TikTokPublicationSettings,UUID>{Optional<TikTokPublicationSettings> findByPublication(Publication p);}
