package com.fdmultimedia.api.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fdmultimedia.api.auth.security.CsrfCookieFilter;
import com.fdmultimedia.api.auth.security.SecurityConfig;
import com.fdmultimedia.api.shared.health.HealthController;
import com.fdmultimedia.api.workers.security.WorkerAuthenticationFilter;
import com.fdmultimedia.api.workers.security.WorkerAuthenticationService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = HealthController.class)
@Import({SecurityConfig.class, CsrfCookieFilter.class, SecurityConfigTest.TestControllerConfiguration.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk());
    }

    @Test
    void applicationApiRejectsUnauthenticatedUsers() throws Exception {
        mockMvc.perform(get("/api/workspaces"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void jobApiRejectsUnauthenticatedUsers() throws Exception {
        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void schedulingApiRejectsUnauthenticatedUsers() throws Exception {
        mockMvc.perform(get("/api/scheduling/overview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void socialAccountsApiRejectsUnauthenticatedUsers() throws Exception {
        mockMvc.perform(get("/api/social-accounts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicationsApiRejectsUnauthenticatedUsers() throws Exception {
        mockMvc.perform(get("/api/publications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void contentDraftsApiRejectsUnauthenticatedUsers() throws Exception {
        mockMvc.perform(get("/api/content-drafts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publishSchedulesApiRejectsUnauthenticatedUsers() throws Exception {
        mockMvc.perform(get("/api/publish-schedules"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void robotsApiRejectsUnauthenticatedUsers() throws Exception {
        mockMvc.perform(get("/api/robots"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void robotRunsApiRejectsUnauthenticatedUsers() throws Exception {
        mockMvc.perform(get("/api/robot-runs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void robotApprovalsApiRejectsUnauthenticatedUsers() throws Exception {
        mockMvc.perform(get("/api/robot-approvals"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void contentSourcesApiRejectsUnauthenticatedUsers() throws Exception {
        mockMvc.perform(get("/api/content-sources"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicMediaEndpointIsReachableWithoutAuthenticationBecauseMetaMustFetchIt() throws Exception {
        mockMvc.perform(get("/api/public-media/some-token"))
                .andExpect(status().isNoContent());
    }

    @Test
    void jobCreationRequiresCsrfToken() throws Exception {
        mockMvc.perform(post("/api/jobs").with(user("owner@example.com")))
                .andExpect(status().isForbidden());
    }

    @Test
    void workerAgentApiRejectsMissingMachineToken() throws Exception {
        mockMvc.perform(post("/api/worker-agent/register"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedPostRequiresCsrfToken() throws Exception {
        mockMvc.perform(post("/api/protected-post").with(user("owner@example.com")))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedPostWithCsrfTokenPassesCsrfProtection() throws Exception {
        mockMvc.perform(post("/api/protected-post").with(user("owner@example.com")).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void protectedPostAcceptsAngularCookieCsrfHeader() throws Exception {
        mockMvc.perform(post("/api/protected-post")
                        .with(user("owner@example.com"))
                        .cookie(new Cookie("XSRF-TOKEN", "browser-token"))
                        .header("X-XSRF-TOKEN", "browser-token"))
                .andExpect(status().isNoContent());
    }

    @TestConfiguration
    static class TestControllerConfiguration {

        @Bean
        ProtectedPostController protectedPostController() {
            return new ProtectedPostController();
        }

        @Bean
        PublicMediaStubController publicMediaStubController() {
            return new PublicMediaStubController();
        }

        @Bean
        WorkerAuthenticationFilter workerAuthenticationFilter() {
            return new WorkerAuthenticationFilter(org.mockito.Mockito.mock(WorkerAuthenticationService.class));
        }
    }

    @RestController
    static final class ProtectedPostController {

        @PostMapping("/api/protected-post")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void protectedPost() {
        }
    }

    /** Stands in for the real PublicMediaController just to verify the security matcher, not streaming behavior. */
    @RestController
    static final class PublicMediaStubController {

        @org.springframework.web.bind.annotation.GetMapping("/api/public-media/{token}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void get(@org.springframework.web.bind.annotation.PathVariable String token) {
        }
    }
}
