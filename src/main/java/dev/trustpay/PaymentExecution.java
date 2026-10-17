package dev.trustpay;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class PaymentExecution {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Provider provider;

  PaymentExecution(
      JdbcTemplate db,
      org.springframework.transaction.PlatformTransactionManager manager,
      Provider provider) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.provider = provider;
  }

  void execute(UUID event, UUID payment) {
    Boolean first =
        tx.execute(
            s -> {
              if (db.update(
                      "INSERT INTO consumed_events(event_id) VALUES (?) ON CONFLICT DO NOTHING",
                      event)
                  == 0) return false;
              int changed =
                  db.update(
                      "UPDATE payments SET status='PROCESSING',updated_at=now() WHERE id=? AND status='CREATED'",
                      payment);
              if (changed == 1)
                db.update(
                    "INSERT INTO audit(merchant,entity_id,action) SELECT merchant,id,'PAYMENT_PROCESSING' FROM payments WHERE id=?",
                    payment);
              return changed == 1;
            });
    if (Boolean.TRUE.equals(first)) provider.submit(payment);
  }
}
