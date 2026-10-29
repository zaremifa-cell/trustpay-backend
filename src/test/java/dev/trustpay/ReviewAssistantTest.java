package dev.trustpay;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** The assistant with a stand-in model: no network, no Docker. */
class ReviewAssistantTest {
  static final ReviewCases.Facts CONFLICT =
      new ReviewCases.Facts(
          "CONFLICTING_PROVIDER_RESULT",
          12_550,
          "EUR",
          "SUCCEEDED",
          true,
          Map.of(),
          List.of("PAYMENT_CREATED", "PAYMENT_SUCCEEDED"));

  final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  final AtomicReference<Prompt> seen = new AtomicReference<>();

  ReviewAssistant assistant(ChatModel model, boolean enabled, Duration timeout) {
    var beans = new StaticListableBeanFactory();
    if (model != null) beans.addBean("chatModel", model);
    return new ReviewAssistant(
        beans.getBeanProvider(ChatModel.class), enabled, timeout, new ObjectMapper(), metrics);
  }

  ChatModel answering(String json) {
    return prompt -> {
      seen.set(prompt);
      return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
    };
  }

  double outcome(String name) {
    return metrics.counter("trustpay.review.assistant", "outcome", name).count();
  }

  @Test
  void validModelAnswerIsUsed() {
    var model =
        answering(
            """
            {"summary":"The provider sent FAILED after SUCCEEDED; the ledger was posted.",
             "resolution":"ESCALATE","confidence":0.9}""");
    var s = assistant(model, true, Duration.ofSeconds(5)).suggest(CONFLICT);
    assertThat(s.source()).isEqualTo("model");
    assertThat(s.resolution()).isEqualTo(ReviewCase.Resolution.ESCALATE);
    assertThat(outcome("model")).isEqualTo(1);
  }

  @Test
  void onlyPaymentFactsAreSentNeverCustomerIdentifiers() {
    var model =
        answering(
            """
            {"summary":"ok","resolution":"KEEP_LEDGER","confidence":0.8}""");
    assistant(model, true, Duration.ofSeconds(5)).suggest(CONFLICT);
    var sent = seen.get().getContents();
    assertThat(sent).contains("CONFLICTING_PROVIDER_RESULT").contains("12550");
    assertThat(sent).doesNotContainIgnoringCase("customer");
  }

  @Test
  void lowConfidenceBecomesEscalateWhateverTheModelSuggests() {
    var model =
        answering(
            """
            {"summary":"Probably the provider is right.","resolution":"ACCEPT_PROVIDER_RESULT","confidence":0.3}""");
    var s = assistant(model, true, Duration.ofSeconds(5)).suggest(CONFLICT);
    assertThat(s.resolution()).isEqualTo(ReviewCase.Resolution.ESCALATE);
  }

  @Test
  void unknownResolutionIsRejectedAndRulesAnswer() {
    var model =
        answering(
            """
            {"summary":"Refund the customer now.","resolution":"REFUND","confidence":0.99}""");
    var s = assistant(model, true, Duration.ofSeconds(5)).suggest(CONFLICT);
    assertThat(s.source()).isEqualTo("rules (model answer rejected)");
    assertThat(s.resolution()).isEqualTo(ReviewCase.Resolution.ESCALATE);
    assertThat(outcome("rejected")).isEqualTo(1);
  }

  @Test
  void malformedOutputFallsBackToRules() {
    var s = assistant(answering("not json at all"), true, Duration.ofSeconds(5)).suggest(CONFLICT);
    assertThat(s.source()).isEqualTo("rules (model unavailable)");
  }

  @Test
  void slowModelTimesOutAndRulesAnswer() {
    ChatModel slow =
        prompt -> {
          try {
            Thread.sleep(5_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return new ChatResponse(List.of());
        };
    var started = System.nanoTime();
    var s = assistant(slow, true, Duration.ofMillis(200)).suggest(CONFLICT);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    assertThat(s.source()).isEqualTo("rules (model timed out)");
  }

  @Test
  void disabledAssistantNeverCallsTheModel() {
    var model = answering("{}");
    var s = assistant(model, false, Duration.ofSeconds(5)).suggest(CONFLICT);
    assertThat(seen.get()).isNull();
    assertThat(s.source()).isEqualTo("rules");
    assertThat(s.summary()).contains("125.50 EUR").contains("ledger posted");
  }
}
