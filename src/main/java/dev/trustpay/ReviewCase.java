package dev.trustpay;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A reconciliation case as an operator sees it: something about a payment needs a human decision.
 *
 * <p>Cases are detected and inserted with plain SQL inside the payment transaction (see {@link
 * Payments#issue}). Reviewing them is ordinary CRUD with optimistic locking, which is where JPA
 * earns its place; see docs/decisions/0001-jdbc-for-money-jpa-for-review.md.
 */
@Entity
@Table(name = "reconciliation_cases")
class ReviewCase {
  enum Status {
    OPEN,
    RESOLVED
  }

  enum Resolution {
    /** The provider is right; finance books the correction outside TrustPay. */
    ACCEPT_PROVIDER_RESULT,
    /** TrustPay's ledger is right; the provider record is ignored. */
    KEEP_LEDGER,
    /** Needs someone with more context than the operator. */
    ESCALATE
  }

  @Id private UUID id;

  @Column(name = "payment_id", nullable = false, updatable = false)
  private UUID paymentId;

  @Column(nullable = false, updatable = false)
  private String reason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status;

  @Enumerated(EnumType.STRING)
  private Resolution resolution;

  @Column(name = "resolution_note")
  private String resolutionNote;

  @Column(name = "resolved_by")
  private String resolvedBy;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Column(name = "assistant_summary")
  private String assistantSummary;

  @Enumerated(EnumType.STRING)
  @Column(name = "assistant_suggestion")
  private Resolution assistantSuggestion;

  @Column(name = "assistant_source")
  private String assistantSource;

  /** Two operators resolving the same case: the second save fails instead of overwriting. */
  @Version private long version;

  protected ReviewCase() {}

  void resolve(Resolution resolution, String note, String operator, Instant at) {
    if (status == Status.RESOLVED) throw new IllegalStateException("Case is already resolved");
    this.status = Status.RESOLVED;
    this.resolution = resolution;
    this.resolutionNote = note;
    this.resolvedBy = operator;
    this.resolvedAt = at;
  }

  void recordSuggestion(ReviewAssistant.Suggestion suggestion) {
    this.assistantSummary = suggestion.summary();
    this.assistantSuggestion = suggestion.resolution();
    this.assistantSource = suggestion.source();
  }

  UUID id() {
    return id;
  }

  UUID paymentId() {
    return paymentId;
  }

  String reason() {
    return reason;
  }

  Instant createdAt() {
    return createdAt;
  }

  Status status() {
    return status;
  }

  Resolution resolution() {
    return resolution;
  }

  String resolutionNote() {
    return resolutionNote;
  }

  String resolvedBy() {
    return resolvedBy;
  }

  Instant resolvedAt() {
    return resolvedAt;
  }

  String assistantSummary() {
    return assistantSummary;
  }

  Resolution assistantSuggestion() {
    return assistantSuggestion;
  }

  String assistantSource() {
    return assistantSource;
  }

  long version() {
    return version;
  }
}
