package dev.trustpay;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Testcontainers
@SpringBootTest(
    properties = {"app.jobs-enabled=false", "spring.rabbitmq.listener.simple.auto-startup=false"})
class PaymentsTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @Autowired Payments payments;
  @Autowired JdbcTemplate db;

  UUID customer(String merchant) {
    var c = payments.customer(merchant);
    payments.identity(c.id(), "VERIFIED");
    return c.id();
  }

  @Test
  void unverifiedCustomerRejected() {
    var c = payments.customer("merchant");
    assertThatThrownBy(() -> payments.create("merchant", "pending", c.id(), 100, "EUR"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void concurrentRetriesCreateExactlyOnePayment() throws Exception {
    var customer = customer("merchant");
    var key = UUID.randomUUID().toString();
    try (var pool = Executors.newFixedThreadPool(20)) {
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<UUID>>();
      for (int i = 0; i < 20; i++)
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return payments.create("merchant", key, customer, 100, "EUR").id();
                }));
      start.countDown();
      var ids = new HashSet<UUID>();
      for (var f : futures) ids.add(f.get(20, TimeUnit.SECONDS));
      assertThat(ids).hasSize(1);
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM outbox WHERE payment_id=?",
                  Integer.class,
                  ids.iterator().next()))
          .isEqualTo(1);
    }
  }

  @Test
  void changedPayloadConflicts() {
    var c = customer("merchant");
    var key = UUID.randomUUID().toString();
    payments.create("merchant", key, c, 100, "EUR");
    assertThatThrownBy(() -> payments.create("merchant", key, c, 200, "EUR"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void duplicateCallbackProducesOneBalancedLedgerTransaction() {
    var p =
        payments.create("merchant", UUID.randomUUID().toString(), customer("merchant"), 100, "EUR");
    payments.settle(p.id(), "SUCCEEDED", 100, "EUR");
    payments.settle(p.id(), "SUCCEEDED", 100, "EUR");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE payment_id=? AND debit_amount=credit_amount",
                Integer.class,
                p.id()))
        .isEqualTo(1);
    assertThat(payments.get("merchant", p.id()).status()).isEqualTo("SUCCEEDED");
  }

  @Test
  void crossMerchantReadDenied() {
    var p =
        payments.create("merchant", UUID.randomUUID().toString(), customer("merchant"), 100, "EUR");
    assertThatThrownBy(() -> payments.get("other", p.id()))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void mismatchCreatesCaseWithoutSettlement() {
    var p =
        payments.create("merchant", UUID.randomUUID().toString(), customer("merchant"), 100, "EUR");
    payments.settle(p.id(), "SUCCEEDED", 200, "EUR");
    assertThat(payments.get("merchant", p.id()).status()).isEqualTo("CREATED");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM reconciliation_cases WHERE payment_id=?",
                Integer.class,
                p.id()))
        .isEqualTo(1);
  }

  @Test
  void conflictingTerminalResultNeverReversesLedger() {
    var p =
        payments.create("merchant", UUID.randomUUID().toString(), customer("merchant"), 100, "EUR");
    payments.settle(p.id(), "SUCCEEDED", 100, "EUR");
    payments.settle(p.id(), "FAILED", 100, "EUR");
    assertThat(payments.get("merchant", p.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM reconciliation_cases WHERE payment_id=?",
                Integer.class,
                p.id()))
        .isEqualTo(1);
  }

  @Test
  void httpAuthenticationAndRoleBoundaries() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/payments"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isUnauthorized());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.httpBasic(
                        "merchant", "merchant-demo")))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/ops/reconcile")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.httpBasic(
                        "merchant", "merchant-demo")))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
  }

  @Test
  void anotherMerchantsCustomerCannotBeUsed() {
    var c = customer("other");
    assertThatThrownBy(
            () -> payments.create("merchant", UUID.randomUUID().toString(), c, 100, "EUR"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void declinedPaymentDoesNotPostLedger() {
    var p =
        payments.create("merchant", UUID.randomUUID().toString(), customer("merchant"), 100, "EUR");
    payments.settle(p.id(), "FAILED", 100, "EUR");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE payment_id=?",
                Integer.class,
                p.id()))
        .isZero();
  }

  @Test
  void concurrentCallbacksPostExactlyOnce() throws Exception {
    var p =
        payments.create("merchant", UUID.randomUUID().toString(), customer("merchant"), 100, "EUR");
    try (var pool = Executors.newFixedThreadPool(10)) {
      var futures = new ArrayList<Future<?>>();
      for (int i = 0; i < 10; i++)
        futures.add(pool.submit(() -> payments.settle(p.id(), "SUCCEEDED", 100, "EUR")));
      for (var future : futures) future.get(20, TimeUnit.SECONDS);
    }
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE payment_id=?",
                Integer.class,
                p.id()))
        .isEqualTo(1);
  }

  @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;

  @Test
  void jwtAuthenticationAndTamperProtection() throws Exception {
    var response =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/token")
                    .with(
                        org.springframework.security.test.web.servlet.request
                            .SecurityMockMvcRequestPostProcessors.httpBasic(
                            "merchant", "merchant-demo")))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();
    var token =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(response.getResponse().getContentAsString())
            .get("access_token")
            .asText();
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments")
                .header("Authorization", "Bearer " + token))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments")
                .header(
                    "Authorization",
                    "Bearer " + token.substring(0, 20) + "!" + token.substring(21)))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isUnauthorized());
  }

  @Test
  void expiredJwtIsRejected() throws Exception {
    var now = java.time.Instant.now();
    var claims =
        org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
            .issuer("trustpay")
            .subject("merchant")
            .issuedAt(now.minusSeconds(900))
            .expiresAt(now.minusSeconds(300))
            .claim("roles", List.of("MERCHANT"))
            .build();
    var header =
        org.springframework.security.oauth2.jwt.JwsHeader.with(
                org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
            .build();
    var token =
        encoder
            .encode(
                org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header, claims))
            .getTokenValue();
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments")
                .header("Authorization", "Bearer " + token))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isUnauthorized());
  }
}
