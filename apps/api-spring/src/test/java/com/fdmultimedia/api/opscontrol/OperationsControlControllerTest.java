package com.fdmultimedia.api.opscontrol;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.opscontrol.OperationsControlService.IncidentsView;
import com.fdmultimedia.api.opscontrol.OpsModels.ComponentStatus;
import com.fdmultimedia.api.opscontrol.OpsModels.IncidentsSection;
import com.fdmultimedia.api.opscontrol.OpsModels.Page;
import com.fdmultimedia.api.shared.web.GlobalExceptionHandler;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class OperationsControlControllerTest {
    private final OperationsControlService service = mock(OperationsControlService.class);
    private final OperatorAccess access = mock(OperatorAccess.class);
    private final AuthenticatedUser user = mock(AuthenticatedUser.class);
    private final UsernamePasswordAuthenticationToken principal = new UsernamePasswordAuthenticationToken(user, null, List.of());
    private final UUID workspace = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new OperationsControlController(service, access))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    private void operator() {
        when(access.require(user)).thenReturn(new OperatorAccess.Operator(userId, workspace, WorkspaceRole.OWNER));
    }

    @Test
    void responsesAreNeverCacheable() throws Exception {
        operator();
        when(service.incidents(eq(workspace), any(), any(), eq(0), eq(25))).thenReturn(
                new IncidentsView(new IncidentsSection(ComponentStatus.HEALTHY, 0, 0, 0, 0, 0), new Page<>(List.of(), 0, 25, 0)));
        mvc.perform(get("/api/operations/incidents").principal(principal)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void incidentsDefaultToActiveFirstPageOfTwentyFive() throws Exception {
        operator();
        when(service.incidents(eq(workspace), eq("ACTIVE"), any(), eq(0), eq(25))).thenReturn(
                new IncidentsView(new IncidentsSection(ComponentStatus.HEALTHY, 0, 0, 0, 0, 0), new Page<>(List.of(), 0, 25, 0)));
        mvc.perform(get("/api/operations/incidents").principal(principal)).andExpect(status().isOk())
                .andExpect(jsonPath("$.incidents.size").value(25));
        verify(service).incidents(workspace, "ACTIVE", null, 0, 25);
    }

    @Test
    void forbiddenOperatorNeverReachesTheService() throws Exception {
        when(access.require(user)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Operator access required"));
        for (String path : List.of("/overview", "/workers", "/jobs", "/schedulers", "/publishing", "/incidents")) {
            mvc.perform(get("/api/operations" + path).principal(principal)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/operations/incidents/" + UUID.randomUUID() + "/acknowledge").principal(principal)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void malformedIdentifiersAreClientErrorsNotServerFaults() throws Exception {
        operator();
        mvc.perform(post("/api/operations/incidents/not-a-uuid/acknowledge").principal(principal))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid value for parameter id"));
        mvc.perform(get("/api/operations/jobs/not-a-uuid").principal(principal)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/operations/jobs").param("page", "abc").principal(principal)).andExpect(status().isBadRequest());
    }

    @Test
    void acknowledgeUsesTheAuthenticatedOperatorAndWorkspaceOnly() throws Exception {
        operator();
        UUID incident = UUID.randomUUID();
        when(service.acknowledge(workspace, userId, incident)).thenReturn(null);
        mvc.perform(post("/api/operations/incidents/" + incident + "/acknowledge").principal(principal)).andExpect(status().isOk());
        verify(service).acknowledge(workspace, userId, incident);
    }
}
