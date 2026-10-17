package dev.trustpay;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
class Messaging {
  @Bean
  org.springframework.amqp.core.Queue deadLetterQueue() {
    return QueueBuilder.durable(QUEUE + ".dead").build();
  }

  static final String QUEUE = "trustpay.payments";

  @Bean
  org.springframework.amqp.core.Queue paymentQueue() {
    return QueueBuilder.durable(QUEUE)
        .deadLetterExchange("")
        .deadLetterRoutingKey(QUEUE + ".dead")
        .build();
  }
}

@Component
@ConditionalOnProperty(name = "app.jobs-enabled", havingValue = "true", matchIfMissing = true)
class OutboxPublisher {
  final JdbcTemplate db;
  final RabbitTemplate rabbit;
  final TransactionTemplate tx;

  OutboxPublisher(
      JdbcTemplate db,
      RabbitTemplate rabbit,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.db = db;
    this.rabbit = rabbit;
    this.tx = new TransactionTemplate(manager);
  }

  @Scheduled(fixedDelay = 1000)
  void publish() {
    tx.executeWithoutResult(
        s -> {
          var rows =
              db.queryForList(
                  "SELECT id,payment_id FROM outbox WHERE published=false ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED");
          for (var row : rows) {
            var correlation = new CorrelationData(row.get("id").toString());
            rabbit.convertAndSend(
                "", Messaging.QUEUE, row.get("id") + ":" + row.get("payment_id"), correlation);
            try {
              var ack = correlation.getFuture().get(5, TimeUnit.SECONDS);
              if (!ack.isAck() || correlation.getReturned() != null)
                throw new IllegalStateException("Broker rejected event");
              db.update("UPDATE outbox SET published=true WHERE id=?", row.get("id"));
            } catch (Exception e) {
              throw new IllegalStateException("Publishing failed; outbox retained", e);
            }
          }
        });
  }
}

@Component
@ConditionalOnProperty(name = "app.jobs-enabled", havingValue = "true", matchIfMissing = true)
class PaymentConsumer {
  final JdbcTemplate db;
  final TransactionTemplate tx;
  final Provider provider;

  PaymentConsumer(
      JdbcTemplate db,
      org.springframework.transaction.PlatformTransactionManager manager,
      Provider provider) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.provider = provider;
  }

  @RabbitListener(queues = Messaging.QUEUE)
  void receive(String message) {
    var parts = message.split(":");
    var event = UUID.fromString(parts[0]);
    var payment = UUID.fromString(parts[1]);
    Boolean first =
        tx.execute(
            s -> {
              if (db.update(
                      "INSERT INTO consumed_events(event_id) VALUES (?) ON CONFLICT DO NOTHING",
                      event)
                  == 0) return false;
              return db.update(
                      "UPDATE payments SET status='PROCESSING',updated_at=now() WHERE id=? AND status='CREATED'",
                      payment)
                  == 1;
            });
    if (Boolean.TRUE.equals(first)) provider.submit(payment);
  }
}
