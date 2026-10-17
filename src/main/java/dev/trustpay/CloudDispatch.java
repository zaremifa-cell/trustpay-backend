package dev.trustpay;

import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Service
@ConditionalOnProperty(name = "app.cloud-adapter", havingValue = "true")
class CloudDispatch {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final RestClient queue;
  private final Provider provider;

  CloudDispatch(
      JdbcTemplate db,
      org.springframework.transaction.PlatformTransactionManager manager,
      Provider provider,
      @Value("${QUEUE_INTERNAL_URL}") String url,
      @Value("${CLOUD_INTERNAL_SECRET}") String secret) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.provider = provider;
    var factory = PlatformHttp.factory(url, 3000, 15000);
    queue =
        RestClient.builder()
            .baseUrl(url)
            .defaultHeader("X-Internal-Secret", secret)
            .requestFactory(factory)
            .build();
  }

  void publish(UUID payment) {
    try {
      tx.executeWithoutResult(
          s -> {
            var events =
                db.queryForList(
                    "SELECT id,payment_id FROM outbox WHERE payment_id=? AND published=false FOR UPDATE SKIP LOCKED",
                    payment);
            for (var event : events) {
              var ack =
                  queue
                      .post()
                      .uri("/api/publish")
                      .body(Map.of("eventId", event.get("id"), "paymentId", payment))
                      .retrieve()
                      .body(java.util.Map.class);
              if (ack == null || !Boolean.TRUE.equals(ack.get("accepted")))
                throw new IllegalStateException("Queue acknowledgement missing");
              db.update("UPDATE outbox SET published=true WHERE id=?", event.get("id"));
            }
          });
    } catch (Exception e) {
      LoggerFactory.getLogger(getClass())
          .warn(
              "Outbox retained for payment {}, error={}, status={}",
              payment,
              e instanceof org.springframework.core.NestedRuntimeException n
                  ? n.getMostSpecificCause().getClass().getSimpleName()
                  : e.getClass().getSimpleName(),
              e instanceof org.springframework.web.client.RestClientResponseException r
                  ? r.getStatusCode().value()
                  : 0);
    }
  }

  void progress(UUID payment) {
    publish(payment);
    var eligible =
        db.queryForObject(
            "SELECT count(*) FROM payments p WHERE p.id=? AND p.status IN ('PROCESSING','UNKNOWN','CREATED') AND p.updated_at < now()-interval '5 seconds' AND EXISTS(SELECT 1 FROM outbox o WHERE o.payment_id=p.id AND o.published=true)",
            Integer.class,
            payment);
    if (eligible != null && eligible > 0) {
      db.update(
          "UPDATE payments SET status='PROCESSING',updated_at=now() WHERE id=? AND status='CREATED'",
          payment);
      try {
        provider.check(payment);
      } catch (Exception e) {
        LoggerFactory.getLogger(getClass()).warn("Recovery deferred for payment {}", payment);
      }
    }
  }
}
