package com.acme.backendfreshsense;

import com.acme.backendfreshsense.accounts.application.dto.LoginRequest;
import com.acme.backendfreshsense.accounts.application.dto.UserRegistrationRequest;
import com.acme.backendfreshsense.accounts.application.dto.UserResponse;
import com.acme.backendfreshsense.accounts.application.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BackendFreshSenseApplicationTests {

    @Autowired
    private AccountService accountService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
    }

    @Test
    @Transactional
    void persistsConsecutiveRefreshTokensAndRevokesPreviousTokens() {
        String email = "refresh-" + UUID.randomUUID() + "@example.test";
        String password = "Test9-" + UUID.randomUUID();
        UserResponse registered = accountService.register(
                new UserRegistrationRequest(email, password, "Refresh Regression"));
        UserResponse loggedIn = accountService.login(new LoginRequest(email, password));
        UserResponse refreshed = accountService.refresh(loggedIn.getRefreshToken());
        UserResponse refreshedAgain = accountService.refresh(refreshed.getRefreshToken());

        assertThat(jdbcTemplate.queryForList(
                "SELECT token FROM refresh_tokens WHERE user_id = ?", String.class, registered.getId()))
                .containsExactlyInAnyOrder(registered.getRefreshToken(), loggedIn.getRefreshToken(),
                        refreshed.getRefreshToken(), refreshedAgain.getRefreshToken())
                .doesNotHaveDuplicates();
        assertThat(jdbcTemplate.queryForList(
                "SELECT token FROM refresh_tokens WHERE user_id = ? AND revoked = false",
                String.class, registered.getId()))
                .containsExactly(refreshedAgain.getRefreshToken());
    }

}
