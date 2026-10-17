package dev.trustpay;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnProperty(name = "app.public-demo", havingValue = "true")
class DemoRateLimit extends OncePerRequestFilter {
  private final Map<String, Integer> counts = new HashMap<>();
  private long minute = -1;
  private int total;

  private synchronized boolean accept(String address, boolean session) {
    long now = System.currentTimeMillis() / 60000;
    if (now != minute) {
      minute = now;
      counts.clear();
      total = 0;
    }
    var key = (session ? "session:" : "api:") + address;
    int limit = session ? 10 : 300;
    if (total >= 1200 || counts.size() >= 2048 || counts.getOrDefault(key, 0) >= limit)
      return false;
    total++;
    counts.merge(key, 1, Integer::sum);
    return true;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if (req.getRequestURI().startsWith("/api/")
        && !req.getRequestURI().startsWith("/api/internal/")
        && !accept(req.getRemoteAddr(), req.getRequestURI().equals("/api/demo/session"))) {
      res.setStatus(429);
      res.setHeader("Retry-After", "60");
      res.setContentType("application/problem+json");
      res.getWriter().write("{\"title\":\"Demo request limit reached\",\"status\":429}");
      return;
    }
    chain.doFilter(req, res);
  }
}
