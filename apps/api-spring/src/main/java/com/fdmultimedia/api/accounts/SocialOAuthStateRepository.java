package com.fdmultimedia.api.accounts;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SocialOAuthStateRepository extends JpaRepository<SocialOAuthState, java.util.UUID> {

    Optional<SocialOAuthState> findByStateHash(String stateHash);

    @Modifying
    @Query("delete from SocialOAuthState s where s.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
