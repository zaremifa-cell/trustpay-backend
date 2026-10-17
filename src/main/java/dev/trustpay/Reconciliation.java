package dev.trustpay;

import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class Reconciliation {
  final JdbcTemplate db;
  final Provider provider;
  final Payments payments;
  final boolean enabled;

  Reconciliation(
      JdbcTemplate db,
      Provider provider,
      Payments payments,
      @Value("${app.jobs-enabled}") boolean enabled) {
    this.db = db;
    this.provider = provider;
    this.payments = payments;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 15000)
  void scheduled() {
    if (enabled) run();
  }

  void run() {
    try {
      provider.reconcileReport();
    } catch (Exception e) {
      LoggerFactory.getLogger(getClass())
          .warn("Provider report unavailable; individual lookups continue");
    }
    for (var row :
        db.queryForList(
            "SELECT p.id FROM payments p WHERE (p.status IN ('PROCESSING','UNKNOWN') OR (p.status='CREATED' AND EXISTS(SELECT 1 FROM outbox o WHERE o.payment_id=p.id AND o.published=true))) AND p.updated_at < now()-interval '5 seconds' ORDER BY p.updated_at LIMIT 100")) {
      try {
        db.update(
            "UPDATE payments SET status='PROCESSING',updated_at=now() WHERE id=? AND status='CREATED'",
            row.get("id"));
        provider.check((UUID) row.get("id"));
      } catch (Exception e) {
        LoggerFactory.getLogger(getClass())
            .warn("Provider reconciliation unavailable for payment {}", row.get("id"));
      }
    }
    for (var row :
        db.queryForList(
            "SELECT p.id FROM payments p LEFT JOIN ledger_transactions l ON l.payment_id=p.id WHERE p.status='SUCCEEDED' AND l.payment_id IS NULL"))
      payments.issue((UUID) row.get("id"), "MISSING_LEDGER");
  }
}
