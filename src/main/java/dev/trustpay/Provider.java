package dev.trustpay;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class Provider {
  record Result(UUID paymentId, String status, long amount, String currency) {}

  final JdbcTemplate db;
  final Payments payments;
  final RestClient client;

  Provider(
      JdbcTemplate db,
      Payments payments,
      @Value("${app.provider-url}") String url,
      @Value("${app.provider-secret}") String secret) {
    this.db = db;
    this.payments = payments;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(1000);
    factory.setReadTimeout(2000);
    client =
        RestClient.builder()
            .baseUrl(url)
            .defaultHeader("X-Provider-Secret", secret)
            .requestFactory(factory)
            .build();
  }

  void submit(UUID id) {
    var r = db.queryForList("SELECT * FROM payments WHERE id=?", id).getFirst();
    try {
      var result =
          client
              .post()
              .uri("/payments")
              .body(
                  Map.of("paymentId", id, "amount", r.get("amount"), "currency", r.get("currency")))
              .retrieve()
              .body(Result.class);
      apply(id, result);
    } catch (Exception e) {
      db.update(
          "UPDATE payments SET status='UNKNOWN',updated_at=now() WHERE id=? AND status='PROCESSING'",
          id);
    }
  }

  void check(UUID id) {
    try {
      var result = client.get().uri("/payments/{id}", id).retrieve().body(Result.class);
      apply(id, result);
    } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
      submit(id);
    }
  }

  void reconcileReport() {
    var report = client.get().uri("/report").retrieve().body(Result[].class);
    if (report == null) throw new IllegalStateException("Empty provider report");
    for (var result : report) {
      if (db.queryForObject(
              "SELECT count(*) FROM payments WHERE id=?", Integer.class, result.paymentId())
          == 1) apply(result.paymentId(), result);
    }
  }

  void apply(UUID expectedId, Result r) {
    if (r == null || !expectedId.equals(r.paymentId())) {
      payments.issue(expectedId, "PROVIDER_PAYMENT_ID_MISMATCH");
      throw new IllegalStateException("Provider response does not match requested operation");
    }
    payments.settle(r.paymentId(), r.status(), r.amount(), r.currency());
  }
}
