package dev.trustpay;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Review cases (JPA), the Redis cache and tracing, against real PostgreSQL and Redis. */
@Testcontainers
@AutoConfigureMockMvc
@AutoConfigureObservability
@SpringBootTest(
    properties = {
      "app.jobs-enabled=false",
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "app.cache=redis"
    })
class ReviewAndCacheTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container
  static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("spring.data.redis.host", redis::getHost);
    r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
  }

  @Autowired Payments payments;
  @Autowired ReviewCases reviews;
  @Autowired JdbcTemplate db;
  @Autowired StringRedisTemplate redisKeys;
  @Autowired MockMvc mvc;

  UUID verifiedCustomer(String merchant) {
    var c = payments.customer(merchant);
    payments.identity(c.id(), "VERIFIED");
    return c.id();
  }

  /** A payment whose provider reported a different amount: one open case. */
  UUID openCase() {
    var p =
        payments.create(
            "merchant", UUID.randomUUID().toString(), verifiedCustomer("merchant"), 100, "EUR");
    payments.settle(p.id(), "SUCCEEDED", 999, "EUR");
    return db.queryForObject(
        "SELECT id FROM reconciliation_cases WHERE payment_id=?", UUID.class, p.id());
  }

  @Test
  void caseIsResolvedOnceAndTheDecisionIsAudited() {
    var id = openCase();
    var resolved =
        reviews.resolve(id, ReviewCase.Resolution.KEEP_LEDGER, "Provider amount was wrong", "ops", 0);
    assertThat(resolved.status()).isEqualTo(ReviewCase.Status.RESOLVED);
    assertThat(resolved.resolvedBy()).isEqualTo("ops");
    assertThat(resolved.version()).isEqualTo(1);
    assertThat(
            db.queryForList(
                "SELECT action FROM audit WHERE entity_id=?", String.class, resolved.paymentId()))
        .contains("REVIEW_KEEP_LEDGER");
    assertThat(reviews.open(100)).extracting(ReviewCases.View::id).doesNotContain(id);

    assertThatThrownBy(
            () -> reviews.resolve(id, ReviewCase.Resolution.ESCALATE, null, "ops", 1))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("already resolved");
  }

  @Test
  void staleVersionIsRejected() {
    var id = openCase();
    reviews.assist(id); // bumps the version
    assertThatThrownBy(
            () -> reviews.resolve(id, ReviewCase.Resolution.ESCALATE, null, "ops", 0))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("changed since you loaded it");
  }

  @Test
  void twoOperatorsResolvingAtOnceExactlyOneWins() throws Exception {
    var id = openCase();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var results = new ArrayList<Future<Boolean>>();
      for (var resolution : List.of(ReviewCase.Resolution.KEEP_LEDGER, ReviewCase.Resolution.ESCALATE))
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    reviews.resolve(id, resolution, null, "ops", 0);
                    return true;
                  } catch (ResponseStatusException e) {
                    return false;
                  }
                }));
      start.countDown();
      var wins = 0;
      for (var r : results) if (r.get(20, TimeUnit.SECONDS)) wins++;
      assertThat(wins).isEqualTo(1);
    }
  }

  @Test
  void opsCanAskTheAssistantOverHttpAndMerchantsCannot() throws Exception {
    var id = openCase();
    mvc.perform(post("/api/ops/review-cases/{id}/assist", id).with(httpBasic("ops", "ops-demo")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assistantSuggestion").value("KEEP_LEDGER"))
        .andExpect(jsonPath("$.assistantSource").value("rules"));
    mvc.perform(
            post("/api/ops/review-cases/{id}/resolve", id)
                .with(httpBasic("merchant", "merchant-demo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resolution\":\"KEEP_LEDGER\",\"version\":1}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/ops/review-cases/{id}/resolve", id)
                .with(httpBasic("ops", "ops-demo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resolution\":\"KEEP_LEDGER\",\"note\":\"checked\",\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RESOLVED"));
  }

  @Test
  void balanceIsCachedAndEvictedAfterSettlementCommits() {
    var merchant = "cache-" + UUID.randomUUID();
    var p = payments.create(merchant, "k1", verifiedCustomer(merchant), 250, "EUR");
    assertThat(payments.balance(merchant)).isEmpty();
    assertThat(redisKeys.hasKey("trustpay:balances::" + merchant)).isTrue();

    payments.settle(p.id(), "SUCCEEDED", 250, "EUR");
    assertThat(redisKeys.hasKey("trustpay:balances::" + merchant)).isFalse();
    assertThat(payments.balance(merchant)).containsExactly(new Payments.Balance("EUR", 250));
  }

  @Test
  void retriesAreAnsweredFromTheCacheButChangedPayloadsStillConflict() {
    var merchant = "replay-" + UUID.randomUUID();
    var customer = verifiedCustomer(merchant);
    var first = payments.create(merchant, "same-key", customer, 500, "EUR");
    assertThat(redisKeys.hasKey("trustpay:idempotency::" + merchant + ":same-key")).isTrue();

    assertThat(payments.create(merchant, "same-key", customer, 500, "EUR").id())
        .isEqualTo(first.id());
    assertThatThrownBy(() -> payments.create(merchant, "same-key", customer, 900, "EUR"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("different request");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM payments WHERE merchant=?", Integer.class, merchant))
        .isEqualTo(1);
  }

  @Test
  void everyResponseCarriesItsTraceId() throws Exception {
    var header =
        mvc.perform(get("/api/ops/review-cases").with(httpBasic("ops", "ops-demo")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("X-Request-Id");
    assertThat(header).matches("[0-9a-f]{32}");
  }
}
