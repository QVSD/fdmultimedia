package com.fdmultimedia.api.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fdmultimedia.api.auth.security.CsrfCookieFilter;
import com.fdmultimedia.api.auth.security.SecurityConfig;
import com.fdmultimedia.api.shared.health.HealthController;
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
    }

    @RestController
    static final class ProtectedPostController {

        @PostMapping("/api/protected-post")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void protectedPost() {
        }
    }
}
