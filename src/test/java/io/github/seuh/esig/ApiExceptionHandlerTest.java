package io.github.seuh.esig;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new VerificationController(null))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void malformedJsonDoesNotEchoSubmittedContent() throws Exception {
        for (String body : new String[] {
                "\"SENSITIVE_MARKER_47\"",
                "{\"base64\":\"SENSITIVE_MARKER_47\",\"filename\":"
        }) {
            String response = mvc.perform(post("/api/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertFalse(response.contains("SENSITIVE_MARKER_47"));
        }
    }
}
