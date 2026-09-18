package com.fdmultimedia.api.assets;

import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.publishing.Publication;
import com.fdmultimedia.api.publishing.PublicationRepository;
import com.fdmultimedia.api.publishing.PublicationStatus;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a validated {@link PublicMediaToken} to the storage key it is
 * allowed to read, inside one transaction so the lazy Publication/asset
 * associations are safe to traverse (the app runs with
 * {@code open-in-view: false}). Every check that decides whether the token is
 * still allowed to read anything lives here, in one place.
 */
@Service
public class PublicMediaAccessService {

    private final PublicationRepository publications;

    public PublicMediaAccessService(PublicationRepository publications) {
        this.publications = publications;
    }

    @Transactional(readOnly = true)
    public Optional<PublicMediaObject> resolve(PublicMediaToken token) {
        Optional<Publication> publicationLookup = publications.findById(token.publicationId());
        if (publicationLookup.isEmpty()) {
            return Optional.empty();
        }
        Publication publication = publicationLookup.get();
        MediaAsset asset = publication.getAsset();
        if (!asset.getId().equals(token.assetId())) {
            return Optional.empty();
        }
        // Only servable while a publish is actively in flight; once the
        // Publication leaves PUBLISHING the token stops working even if it
        // has not technically expired yet.
        if (publication.getStatus() != PublicationStatus.PUBLISHING) {
            return Optional.empty();
        }
        if (publication.getSocialAccount().getPlatform() != SocialPlatform.INSTAGRAM) {
            return Optional.empty();
        }
        String storageKey = asset.getStorageKey();
        if (storageKey == null) {
            return Optional.empty();
        }
        return Optional.of(new PublicMediaObject(storageKey, asset.getContentType(), asset.getFileSizeBytes()));
    }
}
