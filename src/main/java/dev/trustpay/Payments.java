package dev.trustpay;

import static org.springframework.http.HttpStatus.*;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Payments {
  @org.springframework.beans.factory.annotation.Value("${app.public-demo:false}")
  boolean publicDemo;

  private void demoLimit(String merchant, String table, int max) {
    if (!publicDemo || !merchant.startsWith("demo:")) return;
    db.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        Object.class,
        "demo-limit:" + merchant);
    int count =
        table.equals("customers")
            ? db.queryForObject(
                "SELECT count(*) FROM customers WHERE merchant=?", Integer.class, merchant)
            : db.queryForObject(
                "SELECT count(*) FROM payments WHERE merchant=?", Integer.class, merchant);
    if (count >= max)
      throw new ResponseStatusException(
          TOO_MANY_REQUESTS, "Demo session limit reached; start a fresh session");
  }

  private final JdbcTemplate db;

  public Payments(JdbcTemplate db) {
    this.db = db;
  }

  public record Customer(UUID id, String status) {}

  public record Payment(UUID id, UUID customerId, long amount, String currency, String status) {}

  @Transactional
  public Customer customer(String merchant) {
    demoLimit(merchant, "customers", 3);
    var id = UUID.randomUUID();
    db.update(
        "INSERT INTO customers(id,merchant,identity_status) VALUES (?,?,'PENDING')", id, merchant);
    audit(merchant, id, "CUSTOMER_CREATED");
    return new Customer(id, "PENDING");
  }

  @Transactional
  public void identity(UUID id, String status) {
    if (!Set.of("PENDING", "VERIFIED", "REJECTED").contains(status))
      throw new ResponseStatusException(BAD_REQUEST, "Invalid identity status");
    var rows = db.queryForList("SELECT merchant FROM customers WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND);
    db.update("UPDATE customers SET identity_status=? WHERE id=?", status, id);
    audit(rows.getFirst().get("merchant").toString(), id, "IDENTITY_" + status);
  }

  @Transactional
  public Payment create(String merchant, String key, UUID customer, long amount, String currency) {
    if (key == null || key.isBlank() || key.length() > 200)
      throw new ResponseStatusException(
          BAD_REQUEST, "Idempotency-Key required, maximum 200 characters");
    if (amount <= 0 || amount > 10000000 || !Set.of("EUR", "USD", "GBP").contains(currency))
      throw new ResponseStatusException(BAD_REQUEST, "Invalid amount or currency");
    // Serialize matching keys before checking both payload and current identity.
    db.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, merchant + ":" + key);
    var existing =
        db.query(
            "SELECT * FROM payments WHERE merchant=? AND idempotency_key=?",
            (r, n) ->
                new Payment(
                    r.getObject("id", UUID.class),
                    r.getObject("customer_id", UUID.class),
                    r.getLong("amount"),
                    r.getString("currency"),
                    r.getString("status")),
            merchant,
            key);
    if (!existing.isEmpty()) {
      var old = existing.getFirst();
      if (!old.customerId().equals(customer)
          || old.amount() != amount
          || !old.currency().equals(currency))
        throw new ResponseStatusException(CONFLICT, "Key already used with a different request");
      return old;
    }
    demoLimit(merchant, "payments", 25);
    var verified =
        db.queryForList(
            "SELECT identity_status FROM customers WHERE id=? AND merchant=? FOR SHARE",
            customer,
            merchant);
    if (verified.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Customer not found");
    if (!"VERIFIED".equals(verified.getFirst().get("identity_status")))
      throw new ResponseStatusException(UNPROCESSABLE_ENTITY, "Verified identity required");
    var id = UUID.randomUUID();
    db.update(
        "INSERT INTO payments(id,merchant,customer_id,idempotency_key,amount,currency,status) VALUES (?,?,?,?,?,?,'CREATED')",
        id,
        merchant,
        customer,
        key,
        amount,
        currency);
    db.update("INSERT INTO outbox(id,payment_id) VALUES (?,?)", UUID.randomUUID(), id);
    audit(merchant, id, "PAYMENT_CREATED");
    return get(merchant, id);
  }

  public Payment get(String merchant, UUID id) {
    return db
        .query(
            "SELECT * FROM payments WHERE id=? AND merchant=?",
            (r, n) ->
                new Payment(
                    r.getObject("id", UUID.class),
                    r.getObject("customer_id", UUID.class),
                    r.getLong("amount"),
                    r.getString("currency"),
                    r.getString("status")),
            id,
            merchant)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
  }

  public List<Map<String, Object>> list(String merchant, int limit) {
    return db.queryForList(
        "SELECT id,customer_id,amount,currency,status,created_at FROM payments WHERE merchant=? ORDER BY created_at DESC,id LIMIT ?",
        merchant,
        Math.clamp(limit, 1, 100));
  }

  @Transactional
  public void settle(UUID id, String result, long amount, String currency) {
    var rows = db.queryForList("SELECT * FROM payments WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND);
    var row = rows.getFirst();
    var merchant = row.get("merchant").toString();
    if (((Number) row.get("amount")).longValue() != amount
        || !row.get("currency").equals(currency)) {
      issue(id, "PROVIDER_AMOUNT_OR_CURRENCY_MISMATCH");
      return;
    }
    if (!Set.of("SUCCEEDED", "FAILED").contains(result))
      throw new ResponseStatusException(BAD_REQUEST, "Invalid result");
    var previous = row.get("status").toString();
    if (Set.of("SUCCEEDED", "FAILED").contains(previous)) {
      if (!previous.equals(result)) issue(id, "CONFLICTING_PROVIDER_RESULT");
      return;
    }
    if (result.equals("SUCCEEDED"))
      db.update(
          "INSERT INTO ledger_transactions(payment_id,currency,debit_amount,credit_amount,debit_account,credit_account) VALUES (?,?,?,?,?,?)",
          id,
          currency,
          amount,
          amount,
          "provider_clearing",
          "merchant:" + merchant);
    db.update("UPDATE payments SET status=?,updated_at=now() WHERE id=?", result, id);
    audit(merchant, id, "PAYMENT_" + result);
  }

  void audit(String merchant, UUID entity, String action) {
    db.update(
        "INSERT INTO audit(merchant,entity_id,action) VALUES (?,?,?)", merchant, entity, action);
  }

  void issue(UUID id, String reason) {
    db.update(
        "INSERT INTO reconciliation_cases(payment_id,reason) VALUES (?,?) ON CONFLICT DO NOTHING",
        id,
        reason);
  }
}
