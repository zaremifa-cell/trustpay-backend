package dev.trustpay;

import static org.springframework.http.HttpStatus.*;

import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name = "app.public-demo", havingValue = "true")
class DemoApi {
  private final JwtEncoder encoder;
  private final Payments payments;
  private final JdbcTemplate db;
  private final PaymentExecution execution;
  private final String secret;

  DemoApi(
      JwtEncoder encoder,
      Payments payments,
      JdbcTemplate db,
      PaymentExecution execution,
      @Value("${CLOUD_INTERNAL_SECRET:unused-local-secret}") String secret) {
    this.encoder = encoder;
    this.payments = payments;
    this.db = db;
    this.execution = execution;
    this.secret = secret;
  }

  @PostMapping("/api/demo/session")
  Object session() {
    var now = Instant.now();
    var merchant = "demo:" + UUID.randomUUID();
    var claims =
        JwtClaimsSet.builder()
            .issuer("trustpay")
            .subject(merchant)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(900))
            .claim("roles", List.of("MERCHANT"))
            .claim("demo", true)
            .build();
    var token =
        encoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    return Map.of(
        "access_token", token, "token_type", "Bearer", "expires_in", 900, "merchant", merchant);
  }

  private String owner(Jwt jwt) {
    if (jwt == null
        || !Boolean.TRUE.equals(jwt.getClaim("demo"))
        || !jwt.getSubject().startsWith("demo:")) throw new ResponseStatusException(FORBIDDEN);
    return jwt.getSubject();
  }

  @PutMapping("/api/demo/customers/{id}/verify")
  @Transactional
  Object verify(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    var merchant = owner(jwt);
    var rows =
        db.queryForList(
            "SELECT id FROM customers WHERE id=? AND merchant=? FOR UPDATE", id, merchant);
    if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND);
    payments.identity(id, "VERIFIED");
    return Map.of("id", id, "status", "VERIFIED", "simulated", true);
  }

  @PostMapping("/api/demo/payments/{id}/repeat-callback")
  Object repeat(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    var payment = payments.get(owner(jwt), id);
    if (!Set.of("SUCCEEDED", "FAILED").contains(payment.status()))
      throw new ResponseStatusException(CONFLICT, "Wait for a final payment status");
    payments.settle(id, payment.status(), payment.amount(), payment.currency());
    payments.settle(id, payment.status(), payment.amount(), payment.currency());
    return Map.of(
        "paymentId",
        id,
        "ledgerTransactions",
        db.queryForObject(
            "SELECT count(*) FROM ledger_transactions WHERE payment_id=?", Integer.class, id));
  }

  record Event(UUID eventId, UUID paymentId) {}

  @PostMapping("/api/internal/cloud/consume")
  Object consume(@RequestHeader("X-Internal-Secret") String supplied, @RequestBody Event event) {
    if (!java.security.MessageDigest.isEqual(
        secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      throw new ResponseStatusException(UNAUTHORIZED);
    if (event.eventId() == null || event.paymentId() == null)
      throw new ResponseStatusException(BAD_REQUEST);
    if (db.queryForObject(
            "SELECT count(*) FROM outbox WHERE id=? AND payment_id=?",
            Integer.class,
            event.eventId(),
            event.paymentId())
        != 1) throw new ResponseStatusException(NOT_FOUND);
    execution.execute(event.eventId(), event.paymentId());
    return Map.of("received", true);
  }
}
