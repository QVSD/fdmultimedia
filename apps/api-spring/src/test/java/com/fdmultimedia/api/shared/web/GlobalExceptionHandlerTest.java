package com.fdmultimedia.api.shared.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new FailingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void unexpectedExceptionsReturnGenericClientMessage() throws Exception {
        mockMvc.perform(get("/api/failing"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andExpect(content().string(not(containsString("jdbc:postgresql://internal-host"))))
                .andExpect(content().string(not(containsString("database password"))));
    }

    @RestController
    static class FailingController {

        @GetMapping("/api/failing")
        void failing() throws ServletException {
            throw new ServletException(
                    "Failed to connect to jdbc:postgresql://internal-host with database password");
        }
    }
}
