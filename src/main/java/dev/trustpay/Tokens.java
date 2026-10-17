package dev.trustpay;

import java.time.Instant;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;

@RestController
class Tokens {
  private final JwtEncoder encoder;

  Tokens(JwtEncoder encoder) {
    this.encoder = encoder;
  }

  @PostMapping("/api/token")
  Map<String, Object> token(Authentication auth) {
    var now = Instant.now();
    var roles =
        auth.getAuthorities().stream()
            .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
            .toList();
    var claims =
        JwtClaimsSet.builder()
            .issuer("trustpay")
            .subject(auth.getName())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(900))
            .claim("roles", roles)
            .build();
    var header = JwsHeader.with(MacAlgorithm.HS256).build();
    return Map.of(
        "access_token",
        encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(),
        "token_type",
        "Bearer",
        "expires_in",
        900);
  }
}
