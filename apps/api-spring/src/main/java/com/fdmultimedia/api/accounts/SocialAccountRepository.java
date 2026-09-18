package com.fdmultimedia.api.accounts;

import com.fdmultimedia.api.workspaces.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, UUID> {

    List<SocialAccount> findByWorkspaceOrderByCreatedAtDesc(Workspace workspace);

    Optional<SocialAccount> findByWorkspaceAndId(Workspace workspace, UUID id);

    Optional<SocialAccount> findByWorkspaceAndPlatformAndExternalAccountId(Workspace workspace, SocialPlatform platform, String externalAccountId);
}
