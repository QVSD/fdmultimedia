package com.fdmultimedia.api.publishing;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublishingAttemptRepository extends JpaRepository<PublishingAttempt, UUID> {

    List<PublishingAttempt> findByPublicationOrderByJobAttemptAsc(Publication publication);
}
