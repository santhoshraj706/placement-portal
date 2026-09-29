package com.college.placement.registration;

import com.college.placement.common.exception.BadRequestException;
import com.college.placement.registration.dto.CompleteRegistrationRequest;
import com.college.placement.registration.dto.CompleteRegistrationResponse;
import com.college.placement.registration.dto.RequestCodeRequest;
import com.college.placement.registration.dto.RequestCodeResponse;
import com.college.placement.registration.dto.VerifyCodeRequest;
import com.college.placement.registration.dto.VerifyCodeResponse;
import com.college.placement.security.CustomUserDetailsService;
import com.college.placement.security.JwtProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lightweight controller-layer tests for the registration API.
 * Uses @WebMvcTest (no database, no full context) — mocks the service layer.
 */
@WebMvcTest(RegistrationController.class)
@AutoConfigureMockMvc(addFilters = false)   // bypass security filters for controller-level tests
class RegistrationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RegistrationService registrationService;

    // Security beans needed by SecurityConfig / JwtAuthenticationFilter
    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    @DisplayName("POST /request-code → 200 with masked email")
    void requestCodeSuccess() throws Exception {
        when(registrationService.requestCode(any(RequestCodeRequest.class)))
                .thenReturn(RequestCodeResponse.builder()
                        .message("Verification code sent")
                        .maskedEmail("t***********@student.tce.edu")
                        .build());

        String payload = objectMapper.writeValueAsString(
                new RequestCodeRequest("test.student@student.tce.edu", "24C21099"));

        mockMvc.perform(post("/api/auth/registration/request-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Verification code sent"))
                .andExpect(jsonPath("$.maskedEmail").value("t***********@student.tce.edu"));
    }

    @Test
    @DisplayName("POST /request-code with blank email → 400 validation error")
    void requestCodeMissingEmail() throws Exception {
        String payload = objectMapper.writeValueAsString(
                new RequestCodeRequest("", "24C21099"));

        mockMvc.perform(post("/api/auth/registration/request-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /verify-code with wrong code → 400 from service")
    void verifyCodeFailure() throws Exception {
        when(registrationService.verifyCode(any(VerifyCodeRequest.class)))
                .thenThrow(new BadRequestException("Invalid verification code"));

        String payload = objectMapper.writeValueAsString(
                new VerifyCodeRequest("test.student@student.tce.edu", "24C21099", "000000"));

        mockMvc.perform(post("/api/auth/registration/verify-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /verify-code → 200 with registration token")
    void verifyCodeSuccess() throws Exception {
        when(registrationService.verifyCode(any(VerifyCodeRequest.class)))
                .thenReturn(VerifyCodeResponse.builder()
                        .message("Code verified")
                        .registrationToken("jwt-token-here")
                        .build());

        String payload = objectMapper.writeValueAsString(
                new VerifyCodeRequest("test.student@student.tce.edu", "24C21099", "123456"));

        mockMvc.perform(post("/api/auth/registration/verify-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Code verified"))
                .andExpect(jsonPath("$.registrationToken").value("jwt-token-here"));
    }

    @Test
    @DisplayName("POST /complete → 201 with user details")
    void completeRegistrationSuccess() throws Exception {
        when(registrationService.completeRegistration(any(CompleteRegistrationRequest.class)))
                .thenReturn(CompleteRegistrationResponse.builder()
                        .message("Account created successfully")
                        .userId(42L)
                        .name("Test Student")
                        .email("test.student@student.tce.edu")
                        .role("STUDENT")
                        .build());

        String payload = objectMapper.writeValueAsString(
                new CompleteRegistrationRequest("jwt-token", "strongpassword123", "strongpassword123"));

        mockMvc.perform(post("/api/auth/registration/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Account created successfully"))
                .andExpect(jsonPath("$.userId").value(42))
                .andExpect(jsonPath("$.name").value("Test Student"))
                .andExpect(jsonPath("$.email").value("test.student@student.tce.edu"))
                .andExpect(jsonPath("$.role").value("STUDENT"));
    }

    @Test
    @DisplayName("POST /complete with short password → 400 validation error")
    void completeRegistrationShortPassword() throws Exception {
        String payload = objectMapper.writeValueAsString(
                new CompleteRegistrationRequest("jwt-token", "short", "short"));

        mockMvc.perform(post("/api/auth/registration/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }
}
