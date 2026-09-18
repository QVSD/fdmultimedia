package com.fdmultimedia.api.publishing.instagram;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth start/callback for connecting an Instagram account. The authorization
 * code and every provider token stay server-side; the browser only ever sees
 * an authorization URL to navigate to and a final redirect back into the
 * Angular app with a safe, non-sensitive outcome flag.
 */
@RestController
@RequestMapping("/api/social-accounts/instagram")
public class InstagramOAuthController {

    private final InstagramAccountConnectionService connectionService;

    public InstagramOAuthController(InstagramAccountConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    /** Authenticated, CSRF-protected mutation: returns the authorization URL rather than redirecting directly. */
    @PostMapping("/connect")
    public InstagramConnectResponse connect(@AuthenticationPrincipal AuthenticatedUser principal) {
        return new InstagramConnectResponse(connectionService.startAuthorization(principal));
    }

    @GetMapping("/callback")
    public void callback(
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "error", required = false) String error,
            HttpServletResponse response) throws IOException {
        InstagramCallbackResult result = connectionService.handleCallback(state, code, error);
        String query = result.success()
                ? "instagram=connected"
                : "instagram=error&reason=" + URLEncoder.encode(result.failureReason(), StandardCharsets.UTF_8);
        response.sendRedirect("/settings?" + query);
    }
}
