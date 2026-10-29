package dev.trustpay;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every response an X-Request-Id. With tracing on it is the OpenTelemetry trace id, so a
 * client can quote one id that finds the request, its provider calls and its queue messages in
 * Jaeger and in the logs.
 */
@Component
class RequestTracing extends OncePerRequestFilter {
  private final ObjectProvider<Tracer> tracer;

  RequestTracing(ObjectProvider<Tracer> tracer) {
    this.tracer = tracer;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var span = tracer.getIfAvailable() == null ? null : tracer.getObject().currentSpan();
    String id = span != null ? span.context().traceId() : UUID.randomUUID().toString();
    response.setHeader("X-Request-Id", id);
    try (var ignored = MDC.putCloseable("request_id", id)) {
      chain.doFilter(request, response);
    }
  }
}
