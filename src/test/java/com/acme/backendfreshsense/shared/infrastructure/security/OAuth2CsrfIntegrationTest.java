package com.acme.backendfreshsense.shared.infrastructure.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = OAuth2CsrfIntegrationTest.LocalProvider.class)
@AutoConfigureMockMvc
class OAuth2CsrfIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired OAuth2SuccessHandler successHandler;

    @TestConfiguration
    static class LocalProvider {
        @Bean
        ClientRegistrationRepository oauthClients() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("google")
                    .clientId("local-test-client").clientSecret("local-test-fixture")
                    .clientName("Proveedor de prueba")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .authorizationUri("https://identity.example.test/authorize")
                    .tokenUri("http://127.0.0.1:1/token").userInfoUri("http://127.0.0.1:1/user")
                    .userNameAttributeName("sub").scope("profile", "email").build());
        }
    }

    @Test
    void initiatesAuthorizationAndRejectsCallbacksWithoutTheMatchingSessionState() throws Exception {
        var start = mvc.perform(get("/oauth2/authorization/google")).andExpect(status().isFound()).andReturn();
        var location = start.getResponse().getRedirectedUrl();
        assertThat(location).startsWith("https://identity.example.test/authorize?")
                .contains("state=", "response_type=code");
        var pendingSession = (MockHttpSession) start.getRequest().getSession(false);
        assertThat(pendingSession).isNotNull();
        for (var callback : List.of(
                get("/login/oauth2/code/google").param("state", "unsolicited"),
                get("/login/oauth2/code/google").session(pendingSession).param("state", "wrong-for-this-session"))) {
            mvc.perform(callback.param("code", "never-exchange"))
                    .andExpect(status().isFound()).andExpect(redirectedUrl("/login?error"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }
    }

    @Test
    void acceptsTheSpringSuccessHandlerSessionAndProtectsItsRefreshCookie() throws Exception {
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("sub", "fixture", "email", "oauth-" + UUID.randomUUID() + "@example.test",
                        "name", "OAuth laboratorio", "email_verified", true), "sub");
        var response = new MockHttpServletResponse();
        successHandler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:4200/dashboard");
        var access = activeCookie(response, "authToken");
        var refresh = activeCookie(response, "refreshToken");
        assertThat(access.getPath()).isEqualTo("/");
        assertThat(refresh.getPath()).isEqualTo("/api/accounts");
        mvc.perform(get("/api/accounts/me").cookie(access)).andExpect(status().isOk());
        mvc.perform(post("/api/accounts/refresh").cookie(refresh)).andExpect(status().isForbidden());
        mvc.perform(post("/api/accounts/refresh").cookie(refresh).header(ApiCsrfFilter.HEADER, "traceability"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").doesNotHaveJsonPath());
    }

    private static Cookie activeCookie(MockHttpServletResponse response, String name) {
        var cookie = Arrays.stream(response.getCookies())
                .filter(value -> name.equals(value.getName()) && value.getMaxAge() > 0).findFirst().orElseThrow();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        return cookie;
    }
}
