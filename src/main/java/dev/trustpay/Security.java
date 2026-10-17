package dev.trustpay;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class Security {
  @Bean
  javax.crypto.SecretKey signingKey(@Value("${app.jwt-secret}") String secret) {
    if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)
      throw new IllegalArgumentException("JWT secret needs at least 32 bytes");
    return new javax.crypto.spec.SecretKeySpec(
        secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
  }

  @Bean
  org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder(javax.crypto.SecretKey key) {
    return new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(
        new com.nimbusds.jose.jwk.source.ImmutableSecret<>(key));
  }

  @Bean
  org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder(javax.crypto.SecretKey key) {
    var decoder =
        org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withSecretKey(key)
            .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
            .build();
    decoder.setJwtValidator(
        org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer("trustpay"));
    return decoder;
  }

  @Bean
  org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService users(
      @Value("${app.merchant-password}") String merchant,
      @Value("${app.other-password}") String other,
      @Value("${app.ops-password}") String ops) {
    var encoder = new BCryptPasswordEncoder();
    return new InMemoryUserDetailsManager(
        User.withUsername("merchant").password(encoder.encode(merchant)).roles("MERCHANT").build(),
        User.withUsername("other").password(encoder.encode(other)).roles("MERCHANT").build(),
        User.withUsername("ops").password(encoder.encode(ops)).roles("OPS").build());
  }

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    var authorities =
        new org.springframework.security.oauth2.server.resource.authentication
            .JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("roles");
    authorities.setAuthorityPrefix("ROLE_");
    var converter =
        new org.springframework.security.oauth2.server.resource.authentication
            .JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/actuator/health",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/api/provider/webhook")
                    .permitAll()
                    .requestMatchers("/api/token")
                    .authenticated()
                    .requestMatchers("/api/ops/**", "/actuator/**")
                    .hasRole("OPS")
                    .anyRequest()
                    .hasRole("MERCHANT"))
        .httpBasic(b -> {})
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
        .build();
  }
}
