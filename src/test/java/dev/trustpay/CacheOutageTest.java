package dev.trustpay;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Redis configured but unreachable: payments, retries and balances keep working. */
@Testcontainers
@SpringBootTest(
    properties = {
      "app.jobs-enabled=false",
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "app.cache=redis",
      "spring.data.redis.host=127.0.0.1",
      "spring.data.redis.port=1"
    })
class CacheOutageTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired Payments payments;

  @Test
  void paymentsDoNotDependOnTheCache() {
    var merchant = "outage-" + UUID.randomUUID();
    var c = payments.customer(merchant);
    payments.identity(c.id(), "VERIFIED");

    var first = payments.create(merchant, "k", c.id(), 300, "EUR");
    assertThat(payments.create(merchant, "k", c.id(), 300, "EUR").id()).isEqualTo(first.id());
    assertThat(payments.balance(merchant)).isEmpty();

    payments.settle(first.id(), "SUCCEEDED", 300, "EUR");
    assertThat(payments.balance(merchant)).containsExactly(new Payments.Balance("EUR", 300));
  }
}
