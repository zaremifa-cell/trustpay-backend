package dev.trustpay;

import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * Caches are an optimisation, never the source of truth (docs/decisions/0002-cache-is-optional.md):
 *
 * <ul>
 *   <li>{@code balances}: a merchant's balance per currency, evicted whenever a payment settles;
 *   <li>{@code idempotency}: (merchant, key) of payments already created, so retries skip the
 *       advisory lock. A wrong or missing entry only costs a database round trip.
 * </ul>
 *
 * Both are transaction-aware: puts and evictions happen after commit, so a reader can never cache a
 * value from a transaction that later rolls back. Every Redis error is logged and treated as a miss:
 * the database answers and a payment never fails because of the cache.
 */
@Configuration
@EnableCaching
class Caching {
  static final String BALANCES = "balances";
  static final String IDEMPOTENCY = "idempotency";

  @Bean
  @Primary
  CacheManager cacheManager(
      @Value("${app.cache:memory}") String kind,
      org.springframework.beans.factory.ObjectProvider<RedisConnectionFactory> redis) {
    CacheManager store;
    if (kind.equals("redis")) {
      var json =
          RedisSerializationContext.SerializationPair.fromSerializer(
              new GenericJackson2JsonRedisSerializer());
      var defaults =
          RedisCacheConfiguration.defaultCacheConfig()
              .prefixCacheNameWith("trustpay:")
              .serializeValuesWith(json)
              .disableCachingNullValues();
      var manager =
          RedisCacheManager.builder(redis.getObject())
              .withCacheConfiguration(BALANCES, defaults.entryTtl(Duration.ofMinutes(5)))
              .withCacheConfiguration(IDEMPOTENCY, defaults.entryTtl(Duration.ofHours(24)))
              .build();
      manager.afterPropertiesSet();
      store = manager;
    } else {
      store = new ConcurrentMapCacheManager(BALANCES, IDEMPOTENCY);
    }
    // Order matters: the transaction-aware layer defers puts and evictions to after commit, and the
    // fail-safe layer underneath makes sure a cache error then cannot fail an already-committed
    // payment.
    return new CacheManager() {
      @Override
      public Cache getCache(String name) {
        var cache = store.getCache(name);
        return cache == null ? null : new TransactionAwareCacheDecorator(new FailSafeCache(cache));
      }

      @Override
      public java.util.Collection<String> getCacheNames() {
        return store.getCacheNames();
      }
    };
  }

  /** Every cache error becomes a log line and a cache miss. */
  static class FailSafeCache implements Cache {
    static final org.slf4j.Logger log = LoggerFactory.getLogger(FailSafeCache.class);
    final Cache target;

    FailSafeCache(Cache target) {
      this.target = target;
    }

    @Override
    public String getName() {
      return target.getName();
    }

    @Override
    public Object getNativeCache() {
      return target.getNativeCache();
    }

    @Override
    public ValueWrapper get(Object key) {
      try {
        return target.get(key);
      } catch (RuntimeException e) {
        log.warn("Cache {} unavailable on read, using the database: {}", getName(), e.toString());
        return null;
      }
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
      try {
        return target.get(key, type);
      } catch (RuntimeException e) {
        log.warn("Cache {} unavailable on read, using the database: {}", getName(), e.toString());
        return null;
      }
    }

    @Override
    public <T> T get(Object key, java.util.concurrent.Callable<T> loader) {
      try {
        return target.get(key, loader);
      } catch (ValueRetrievalException e) {
        throw e;
      } catch (RuntimeException e) {
        log.warn("Cache {} unavailable on read, using the database: {}", getName(), e.toString());
        try {
          return loader.call();
        } catch (Exception inner) {
          throw new ValueRetrievalException(key, loader, inner);
        }
      }
    }

    @Override
    public void put(Object key, Object value) {
      try {
        target.put(key, value);
      } catch (RuntimeException e) {
        log.warn("Cache {} unavailable on write: {}", getName(), e.toString());
      }
    }

    @Override
    public void evict(Object key) {
      try {
        target.evict(key);
      } catch (RuntimeException e) {
        log.error("Cache {} eviction failed for {}; the entry expires by TTL", getName(), key);
      }
    }

    @Override
    public void clear() {
      try {
        target.clear();
      } catch (RuntimeException e) {
        log.error("Cache {} clear failed: {}", getName(), e.toString());
      }
    }
  }
}
