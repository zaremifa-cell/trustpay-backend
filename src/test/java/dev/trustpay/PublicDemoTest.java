package dev.trustpay;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
    properties = {
      "app.public-demo=true",
      "app.cloud-adapter=true",
      "app.jobs-enabled=false",
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "CLOUD_INTERNAL_SECRET=unit-test-internal-secret"
    })
class PublicDemoTest {
  static final com.sun.net.httpserver.HttpServer bridge;
  static final java.util.concurrent.atomic.AtomicBoolean available =
      new java.util.concurrent.atomic.AtomicBoolean(true);

  static {
    try {
      bridge =
          com.sun.net.httpserver.HttpServer.create(
              new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      bridge.createContext(
          "/api/publish",
          e -> {
            e.getRequestBody().readAllBytes();
            byte[] b = "{\"accepted\":true,\"messageId\":null}".getBytes();
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(available.get() ? 200 : 503, b.length);
            e.getResponseBody().write(b);
            e.close();
          });
      bridge.createContext(
          "/payments",
          e -> {
            byte[] b = e.getRequestBody().readAllBytes();
            String response = new String(b).replace("}", ",\"status\":\"SUCCEEDED\"}");
            b = response.getBytes();
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, b.length);
            e.getResponseBody().write(b);
            e.close();
          });
      bridge.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @AfterAll
  static void stopBridge() {
    bridge.stop(0);
  }

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("QUEUE_INTERNAL_URL", () -> "http://127.0.0.1:" + bridge.getAddress().getPort());
    r.add("app.provider-url", () -> "http://127.0.0.1:" + bridge.getAddress().getPort());
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired org.springframework.jdbc.core.JdbcTemplate db;
  @Autowired CloudDispatch dispatch;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired Payments payments;

  String session() throws Exception {
    return json.readTree(
            mvc.perform(post("/api/demo/session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("access_token")
        .asText();
  }

  @Test
  void isolatedSessionCanVerifyOnlyItsOwnCustomer() throws Exception {
    var a = session();
    var b = session();
    var response =
        mvc.perform(post("/api/customers").header("Authorization", "Bearer " + a))
            .andExpect(status().isCreated())
            .andReturn();
    var id = json.readTree(response.getResponse().getContentAsString()).get("id").asText();
    mvc.perform(put("/api/demo/customers/" + id + "/verify").header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
    mvc.perform(put("/api/demo/customers/" + id + "/verify").header("Authorization", "Bearer " + a))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/payments")
                .header("Authorization", "Bearer " + a)
                .header("Idempotency-Key", "demo-order")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + id + "\",\"amount\":10000,\"currency\":\"EUR\"}"))
        .andExpect(status().isAccepted());
  }

  @Test
  void publicDemoRejectsBasicAndOperationsAccess() throws Exception {
    mvc.perform(
            get("/api/payments")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.httpBasic("ops", "ops-demo")))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/ops/reconcile").header("Authorization", "Bearer " + session()))
        .andExpect(status().isForbidden());
  }

  @Test
  void consumerRequiresPrivateSecret() throws Exception {
    mvc.perform(
            post("/api/internal/cloud/consume")
                .header("X-Internal-Secret", "wrong")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"eventId\":\""
                        + UUID.randomUUID()
                        + "\",\"paymentId\":\""
                        + UUID.randomUUID()
                        + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void sessionCustomerLimitIsEnforced() throws Exception {
    var token = session();
    for (int i = 0; i < 3; i++)
      mvc.perform(post("/api/customers").header("Authorization", "Bearer " + token))
          .andExpect(status().isCreated());
    mvc.perform(post("/api/customers").header("Authorization", "Bearer " + token))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void queueDeliveryAndDuplicateConsumptionSettleOnce() throws Exception {
    var c = payments.customer("cloud-worker-test");
    payments.identity(c.id(), "VERIFIED");
    var p = payments.create("cloud-worker-test", "worker-order", c.id(), 10000, "EUR");
    dispatch.publish(p.id());
    assertThat(
            db.queryForObject(
                "SELECT published FROM outbox WHERE payment_id=?", Boolean.class, p.id()))
        .isTrue();
    var event = db.queryForObject("SELECT id FROM outbox WHERE payment_id=?", UUID.class, p.id());
    String body = json.writeValueAsString(Map.of("eventId", event, "paymentId", p.id()));
    for (int i = 0; i < 2; i++)
      mvc.perform(
              post("/api/internal/cloud/consume")
                  .header("X-Internal-Secret", "unit-test-internal-secret")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isOk());
    assertThat(payments.get("cloud-worker-test", p.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE payment_id=?",
                Integer.class,
                p.id()))
        .isEqualTo(1);
  }

  @Test
  void unavailableQueueRetainsOutboxForRetry() {
    var c = payments.customer("cloud-retry-test");
    payments.identity(c.id(), "VERIFIED");
    var p = payments.create("cloud-retry-test", "retry-order", c.id(), 10000, "EUR");
    available.set(false);
    try {
      dispatch.publish(p.id());
      assertThat(
              db.queryForObject(
                  "SELECT published FROM outbox WHERE payment_id=?", Boolean.class, p.id()))
          .isFalse();
    } finally {
      available.set(true);
    }
    dispatch.publish(p.id());
    assertThat(
            db.queryForObject(
                "SELECT published FROM outbox WHERE payment_id=?", Boolean.class, p.id()))
        .isTrue();
  }
}
