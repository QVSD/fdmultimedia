package com.fdmultimedia.api.contentsources;

import com.fdmultimedia.api.assets.MediaAsset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentSourceAssetRepository extends JpaRepository<ContentSourceAsset, UUID> {

    List<ContentSourceAsset> findByContentSourceOrderByAddedAtAscIdAsc(ContentSource contentSource);

    boolean existsByContentSourceAndMediaAsset(ContentSource contentSource, MediaAsset mediaAsset);

    Optional<ContentSourceAsset> findByContentSourceAndMediaAsset(ContentSource contentSource, MediaAsset mediaAsset);

    long countByContentSource(ContentSource contentSource);
}
