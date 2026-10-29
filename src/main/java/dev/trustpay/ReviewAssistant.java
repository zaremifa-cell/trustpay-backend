package dev.trustpay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Suggests how to resolve a review case. It advises; it never acts: the suggestion is stored next
 * to the case and an operator still has to resolve it. See docs/decisions/0003-ai-suggests-never-acts.md.
 *
 * <p>With {@code app.ai.enabled=true} a language model (Spring AI, Ollama by default) writes the
 * summary. Its answer is validated before anyone sees it, and every failure (disabled, timeout,
 * unreachable model, malformed or out-of-policy answer) falls back to deterministic rules, so the
 * endpoint always answers.
 */
@Component
class ReviewAssistant {
  record Suggestion(String summary, ReviewCase.Resolution resolution, String source) {}

  /** The shape the model must answer in (Spring AI structured output). */
  record ModelAnswer(String summary, String resolution, Double confidence) {}

  static final int MAX_SUMMARY = 600;
  static final double MIN_CONFIDENCE = 0.6;

  static final String SYSTEM =
      """
      You help payment-operations staff review a reconciliation case in a payment system.
      You receive only facts about one payment. Write a short summary (at most 3 sentences) of what
      happened and why it needs review, then suggest one resolution:
      ACCEPT_PROVIDER_RESULT - the payment provider's record is correct;
      KEEP_LEDGER - the system's own ledger is correct;
      ESCALATE - the facts are not enough to decide.
      Use only the facts given. Do not invent amounts, dates or parties. If unsure, choose ESCALATE.
      Give your confidence between 0 and 1.
      """;

  final ObjectProvider<ChatModel> model;
  final boolean enabled;
  final Duration timeout;
  final ObjectMapper json;
  final MeterRegistry metrics;
  final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

  ReviewAssistant(
      ObjectProvider<ChatModel> model,
      @Value("${app.ai.enabled:false}") boolean enabled,
      @Value("${app.ai.timeout:10s}") Duration timeout,
      ObjectMapper json,
      MeterRegistry metrics) {
    this.model = model;
    this.enabled = enabled;
    this.timeout = timeout;
    this.json = json;
    this.metrics = metrics;
  }

  Suggestion suggest(ReviewCases.Facts facts) {
    var chat = enabled ? model.getIfAvailable() : null;
    if (chat == null) return count("rules", rules(facts, "rules"));
    var call = calls.submit(() -> ask(chat, facts));
    try {
      var answer = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      var validated = validate(answer, facts);
      if (validated != null) return count("model", validated);
      return count("rejected", rules(facts, "rules (model answer rejected)"));
    } catch (TimeoutException e) {
      call.cancel(true);
      return count("timeout", rules(facts, "rules (model timed out)"));
    } catch (Exception e) {
      LoggerFactory.getLogger(getClass()).warn("Review assistant unavailable: {}", e.toString());
      return count("error", rules(facts, "rules (model unavailable)"));
    }
  }

  ModelAnswer ask(ChatModel chat, ReviewCases.Facts facts) throws Exception {
    return ChatClient.create(chat)
        .prompt()
        .system(SYSTEM)
        .user(json.writeValueAsString(facts))
        .call()
        .entity(ModelAnswer.class);
  }

  /** Returns null when the answer must not be shown to an operator. */
  static Suggestion validate(ModelAnswer answer, ReviewCases.Facts facts) {
    if (answer == null || answer.summary() == null || answer.summary().isBlank()) return null;
    if (answer.summary().length() > MAX_SUMMARY) return null;
    ReviewCase.Resolution resolution;
    try {
      resolution = ReviewCase.Resolution.valueOf(String.valueOf(answer.resolution()).trim());
    } catch (IllegalArgumentException e) {
      return null;
    }
    // Policy, not model judgement: low confidence always becomes ESCALATE.
    if (answer.confidence() == null || answer.confidence() < MIN_CONFIDENCE)
      resolution = ReviewCase.Resolution.ESCALATE;
    return new Suggestion(answer.summary().trim(), resolution, "model");
  }

  /** Deterministic fallback: what an experienced operator would do first for each reason. */
  static Suggestion rules(ReviewCases.Facts f, String source) {
    var money = "%d.%02d %s".formatted(f.amount() / 100, f.amount() % 100, f.currency());
    return switch (f.reason()) {
      case "PROVIDER_AMOUNT_OR_CURRENCY_MISMATCH" ->
          new Suggestion(
              "The provider reported a different amount or currency than the "
                  + money
                  + " payment. The payment was not settled and no ledger entry exists.",
              ReviewCase.Resolution.KEEP_LEDGER,
              source);
      case "PROVIDER_PAYMENT_ID_MISMATCH" ->
          new Suggestion(
              "The provider answered for a different payment than the one requested ("
                  + money
                  + "). Its answer was not applied.",
              ReviewCase.Resolution.KEEP_LEDGER,
              source);
      case "CONFLICTING_PROVIDER_RESULT" ->
          new Suggestion(
              "The provider sent a second, contradicting final result for a payment that is already "
                  + f.paymentStatus()
                  + " ("
                  + money
                  + (f.ledgerPosted() ? ", ledger posted" : ", no ledger entry")
                  + "). The first result was kept.",
              ReviewCase.Resolution.ESCALATE,
              source);
      case "MISSING_LEDGER" ->
          new Suggestion(
              "The payment of "
                  + money
                  + " is marked SUCCEEDED but has no ledger entry. Money may have moved without"
                  + " being booked.",
              ReviewCase.Resolution.ESCALATE,
              source);
      default ->
          new Suggestion(
              "Unrecognised case (" + f.reason() + ") for a " + money + " payment.",
              ReviewCase.Resolution.ESCALATE,
              source);
    };
  }

  Suggestion count(String outcome, Suggestion suggestion) {
    metrics.counter("trustpay.review.assistant", "outcome", outcome).increment();
    return suggestion;
  }
}
