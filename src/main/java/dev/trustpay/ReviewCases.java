package dev.trustpay;

import static org.springframework.http.HttpStatus.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

interface ReviewCaseRepository extends JpaRepository<ReviewCase, UUID> {
  List<ReviewCase> findByStatusOrderByCreatedAtAsc(ReviewCase.Status status, Limit limit);

  long countByStatus(ReviewCase.Status status);
}

/** Operators work through open reconciliation cases: read the facts, optionally ask, decide. */
@Service
class ReviewCases {
  /** What a case is about, as facts only: no customer identifiers leave this method. */
  record Facts(
      String reason,
      long amount,
      String currency,
      String paymentStatus,
      boolean ledgerPosted,
      Map<String, Object> providerRecord,
      List<String> auditTrail) {}

  record View(
      UUID id,
      UUID paymentId,
      String reason,
      Instant createdAt,
      ReviewCase.Status status,
      ReviewCase.Resolution resolution,
      String resolutionNote,
      String resolvedBy,
      Instant resolvedAt,
      String assistantSummary,
      ReviewCase.Resolution assistantSuggestion,
      String assistantSource,
      long version) {
    static View of(ReviewCase c) {
      return new View(
          c.id(),
          c.paymentId(),
          c.reason(),
          c.createdAt(),
          c.status(),
          c.resolution(),
          c.resolutionNote(),
          c.resolvedBy(),
          c.resolvedAt(),
          c.assistantSummary(),
          c.assistantSuggestion(),
          c.assistantSource(),
          c.version());
    }
  }

  final ReviewCaseRepository cases;
  final JdbcTemplate db;
  final Payments payments;
  final ReviewAssistant assistant;
  final TransactionTemplate tx;

  ReviewCases(
      ReviewCaseRepository cases,
      JdbcTemplate db,
      Payments payments,
      ReviewAssistant assistant,
      TransactionTemplate tx) {
    this.cases = cases;
    this.db = db;
    this.payments = payments;
    this.assistant = assistant;
    this.tx = tx;
  }

  @Transactional(readOnly = true)
  public List<View> open(int limit) {
    return cases
        .findByStatusOrderByCreatedAtAsc(ReviewCase.Status.OPEN, Limit.of(Math.clamp(limit, 1, 100)))
        .stream()
        .map(View::of)
        .toList();
  }

  @Transactional(readOnly = true)
  public View get(UUID id) {
    return View.of(find(id));
  }

  @Transactional(readOnly = true)
  public Facts facts(UUID id) {
    var c = find(id);
    var payment = db.queryForMap("SELECT amount,currency,status FROM payments WHERE id=?", c.paymentId());
    var provider =
        db.queryForList(
            "SELECT payload::text AS payload FROM provider_records WHERE payment_id=?",
            c.paymentId());
    return new Facts(
        c.reason(),
        ((Number) payment.get("amount")).longValue(),
        payment.get("currency").toString(),
        payment.get("status").toString(),
        db.queryForObject(
            "SELECT count(*) FROM ledger_transactions WHERE payment_id=?",
            Integer.class,
            c.paymentId())
            > 0,
        provider.isEmpty() ? Map.of() : Map.of("payload", provider.getFirst().get("payload")),
        db.queryForList(
            "SELECT action FROM audit WHERE entity_id=? ORDER BY id", String.class, c.paymentId()));
  }

  /**
   * Asks the assistant for a summary and a suggested resolution and stores both on the case. The
   * call happens outside any transaction: a slow model must not hold a database connection.
   */
  public View assist(UUID id) {
    var suggestion = assistant.suggest(tx.execute(s -> facts(id)));
    return tx.execute(
        s -> {
          var c = find(id);
          c.recordSuggestion(suggestion);
          return View.of(cases.saveAndFlush(c));
        });
  }

  @Transactional
  public View resolve(UUID id, ReviewCase.Resolution resolution, String note, String operator, long version) {
    var c = find(id);
    if (c.status() == ReviewCase.Status.RESOLVED)
      throw new ResponseStatusException(CONFLICT, "Case is already resolved");
    if (c.version() != version)
      throw new ResponseStatusException(CONFLICT, "Case changed since you loaded it; reload it");
    c.resolve(resolution, note, operator, Instant.now());
    var merchant =
        db.queryForObject("SELECT merchant FROM payments WHERE id=?", String.class, c.paymentId());
    payments.audit(merchant, c.paymentId(), "REVIEW_" + resolution);
    try {
      return View.of(cases.saveAndFlush(c));
    } catch (OptimisticLockingFailureException e) {
      throw new ResponseStatusException(CONFLICT, "Case changed since you loaded it; reload it");
    }
  }

  private ReviewCase find(UUID id) {
    return cases
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Review case not found"));
  }
}

@RestController
@RequestMapping("/api/ops/review-cases")
class ReviewApi {
  record Resolve(
      @NotNull ReviewCase.Resolution resolution,
      @Size(max = 500) String note,
      @PositiveOrZero long version) {}

  final ReviewCases reviews;

  ReviewApi(ReviewCases reviews) {
    this.reviews = reviews;
  }

  @GetMapping
  List<ReviewCases.View> open(@RequestParam(defaultValue = "50") int limit) {
    return reviews.open(limit);
  }

  @GetMapping("/{id}")
  Map<String, Object> get(@PathVariable UUID id) {
    return Map.of("case", reviews.get(id), "facts", reviews.facts(id));
  }

  @PostMapping("/{id}/assist")
  ReviewCases.View assist(@PathVariable UUID id) {
    return reviews.assist(id);
  }

  @PostMapping("/{id}/resolve")
  ReviewCases.View resolve(
      Principal operator, @PathVariable UUID id, @Valid @RequestBody Resolve request) {
    return reviews.resolve(
        id, request.resolution(), request.note(), operator.getName(), request.version());
  }
}
