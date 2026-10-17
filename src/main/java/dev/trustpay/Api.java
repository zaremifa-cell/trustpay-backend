package dev.trustpay;

import static org.springframework.http.HttpStatus.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
class Api {
  private final Payments payments;
  private final JdbcTemplate db;
  private final String secret;
  private final Reconciliation reconciliation;
  private final org.springframework.beans.factory.ObjectProvider<CloudDispatch> cloud;

  Api(
      Payments payments,
      JdbcTemplate db,
      @Value("${app.provider-secret}") String secret,
      Reconciliation reconciliation,
      org.springframework.beans.factory.ObjectProvider<CloudDispatch> cloud) {
    this.payments = payments;
    this.db = db;
    this.secret = secret;
    this.reconciliation = reconciliation;
    this.cloud = cloud;
  }

  record Create(
      @NotNull UUID customerId,
      @Min(1) @Max(10000000) long amount,
      @NotNull @Pattern(regexp = "EUR|USD|GBP") String currency) {}

  record Identity(@NotNull @Pattern(regexp = "PENDING|VERIFIED|REJECTED") String status) {}

  record Callback(
      @NotNull UUID paymentId,
      @NotNull @Pattern(regexp = "SUCCEEDED|FAILED") String status,
      @Min(1) long amount,
      @NotNull String currency) {}

  @PostMapping("/customers")
  @ResponseStatus(CREATED)
  Payments.Customer customer(Principal p) {
    return payments.customer(p.getName());
  }

  @PostMapping("/payments")
  @ResponseStatus(ACCEPTED)
  Payments.Payment create(
      Principal p, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Create r) {
    var payment = payments.create(p.getName(), key, r.customerId(), r.amount(), r.currency());
    cloud.ifAvailable(dispatch -> dispatch.publish(payment.id()));
    return payment;
  }

  @GetMapping("/payments/{id}")
  Payments.Payment get(Principal p, @PathVariable UUID id) {
    payments.get(p.getName(), id);
    cloud.ifAvailable(dispatch -> dispatch.progress(id));
    return payments.get(p.getName(), id);
  }

  @GetMapping("/payments")
  Object list(Principal p, @RequestParam(defaultValue = "20") int limit) {
    return payments.list(p.getName(), limit);
  }

  @GetMapping("/balance")
  Object balance(Principal p) {
    return db.queryForList(
        "SELECT currency,sum(credit_amount) AS amount FROM ledger_transactions WHERE credit_account=? GROUP BY currency",
        "merchant:" + p.getName());
  }

  @GetMapping("/payments/{id}/ledger")
  Object ledger(Principal p, @PathVariable UUID id) {
    payments.get(p.getName(), id);
    return db.queryForList("SELECT * FROM ledger_transactions WHERE payment_id=?", id);
  }

  @GetMapping("/payments/{id}/audit")
  Object audit(Principal p, @PathVariable UUID id) {
    payments.get(p.getName(), id);
    return db.queryForList(
        "SELECT action,created_at FROM audit WHERE entity_id=? AND merchant=? ORDER BY id",
        id,
        p.getName());
  }

  @PutMapping("/ops/customers/{id}/identity")
  void identity(@PathVariable UUID id, @Valid @RequestBody Identity r) {
    payments.identity(id, r.status());
  }

  @PostMapping("/provider/webhook")
  void callback(
      @RequestHeader("X-Provider-Secret") String supplied, @Valid @RequestBody Callback r) {
    if (!java.security.MessageDigest.isEqual(
        secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      throw new ResponseStatusException(UNAUTHORIZED);
    payments.settle(r.paymentId(), r.status(), r.amount(), r.currency());
  }

  @PostMapping("/ops/reconcile")
  Object reconcile() {
    reconciliation.run();
    return Map.of("status", "completed");
  }

  @GetMapping("/ops/reconciliation-cases")
  Object cases() {
    return db.queryForList("SELECT * FROM reconciliation_cases ORDER BY created_at DESC LIMIT 100");
  }
}
