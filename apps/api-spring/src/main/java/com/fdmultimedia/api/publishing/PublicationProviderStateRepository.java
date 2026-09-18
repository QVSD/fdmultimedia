package com.fdmultimedia.api.publishing;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicationProviderStateRepository extends JpaRepository<PublicationProviderState, java.util.UUID> {

    Optional<PublicationProviderState> findByPublication(Publication publication);
}
