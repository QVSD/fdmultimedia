package com.fdmultimedia.api.robotchanges;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fdmultimedia.api.auth.AuthService;import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.robotchanges.RobotAdaptivePolicyModels.*;import com.fdmultimedia.api.robots.*;
import com.fdmultimedia.api.users.AppUser;import com.fdmultimedia.api.workspaces.*;
import java.time.*;import java.util.*;import org.junit.jupiter.api.*;import org.springframework.web.server.ResponseStatusException;

class RobotAdaptivePolicyServiceTest {
    private static final Instant NOW=Instant.parse("2026-10-03T12:00:00Z");
    private final AuthService auth=mock(AuthService.class);private final RobotRepository robots=mock(RobotRepository.class);
    private final RobotAdaptivePolicyRepository policies=mock(RobotAdaptivePolicyRepository.class);
    private final RobotAdaptivePolicyRevisionRepository history=mock(RobotAdaptivePolicyRevisionRepository.class);
    private final RobotAdaptivePolicyService service=new RobotAdaptivePolicyService(auth,robots,policies,history,Clock.fixed(NOW,ZoneOffset.UTC));
    private final Workspace workspace=new Workspace("W","w");private final AppUser owner=new AppUser("a@b.test","h","A");
    private final AuthenticatedUser principal=new AuthenticatedUser(owner);private final UUID robotId=UUID.randomUUID();
    @BeforeEach void setup(){when(auth.currentMembershipFor(principal)).thenReturn(new WorkspaceMembership(workspace,owner,WorkspaceRole.OWNER));
        when(robots.findByWorkspaceAndId(workspace,robotId)).thenReturn(Optional.of(mock(Robot.class)));when(robots.findByWorkspaceAndIdForUpdate(workspace,robotId)).thenReturn(Optional.of(mock(Robot.class)));}
    @Test void existingRobotResolvesConservativeDefaultsWithoutBackfill(){PolicySummary p=service.get(principal,robotId);assertThat(p.persisted()).isFalse();assertThat(p.enabled()).isTrue();
        assertThat(p.maxAppliedChangesPerWindow()).isEqualTo(2);assertThat(p.changeBudgetWindowDays()).isEqualTo(30);assertThat(p.cooldownHours()).isEqualTo(72);}
    @Test void boundsAreServerAuthoritative(){UpdateRequest bad=new UpdateRequest(0,true,0,30,72,true,true,true);
        assertThatThrownBy(()->service.update(principal,robotId,bad)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("between 1 and 10");}
    @Test void staleExpectedRevisionPreventsLostUpdate(){RobotAdaptivePolicy existing=new RobotAdaptivePolicy(workspace,robotId,owner,NOW);when(policies.findForUpdate(workspace,robotId)).thenReturn(Optional.of(existing));
        UpdateRequest request=new UpdateRequest(0,true,2,30,72,true,true,true);assertThatThrownBy(()->service.update(principal,robotId,request)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("REVISION_CONFLICT");}
    @Test void explicitUpdateCreatesImmutableRevisionOne(){when(policies.findForUpdate(workspace,robotId)).thenReturn(Optional.empty());when(policies.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));
        PolicySummary p=service.update(principal,robotId,new UpdateRequest(0,true,3,45,24,true,true,true));assertThat(p.revision()).isEqualTo(1);verify(history).saveAndFlush(any(RobotAdaptivePolicyRevision.class));}
}
