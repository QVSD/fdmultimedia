package com.fdmultimedia.api.accounts;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialAccountCredentialRepository extends JpaRepository<SocialAccountCredential, UUID> {

    Optional<SocialAccountCredential> findBySocialAccount(SocialAccount socialAccount);

    void deleteBySocialAccount(SocialAccount socialAccount);
}
