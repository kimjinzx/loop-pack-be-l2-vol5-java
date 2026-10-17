package com.loopers.infrastructure.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.ProductCache;
import com.loopers.application.product.ProductSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 키에 상품 revision이 들어가서 한 키의 값은 바뀌지 않는다. 여러 상품은 MGET 한 번으로 읽는다
@Slf4j
@RequiredArgsConstructor
@Component
public class RedisProductCache implements ProductCache {

    static final String METRIC = "product.cache";

    private static final String KEY_PREFIX = "product:detail:v1";

    // revision이 바뀌어 더 읽히지 않는 키를 정리하는 수명이다. 신선도는 revision이 맡는다
    private static final Duration TTL = Duration.ofMinutes(10);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    public Map<Long, ProductSnapshot> findAll(Map<Long, Long> revisionsByProductId) {
        if (revisionsByProductId.isEmpty()) {
            return Map.of();
        }
        List<Long> productIds = new ArrayList<>(revisionsByProductId.keySet());
        List<String> keys = productIds.stream().map(id -> toRedisKey(id, revisionsByProductId.get(id))).toList();
        List<String> values;
        try {
            values = redisTemplate.opsForValue().multiGet(keys);
        } catch (DataAccessException e) {
            count("get", "error", productIds.size());
            log.warn("상품 캐시 조회 실패, DB에서 조회한다: {}", e.getMessage());
            return Map.of();
        }

        Map<Long, ProductSnapshot> found = new HashMap<>();
        for (int i = 0; i < productIds.size(); i++) {
            String value = values == null ? null : values.get(i);
            if (value == null) {
                count("get", "miss", 1);
                continue;
            }
            try {
                found.put(productIds.get(i), objectMapper.readValue(value, ProductSnapshot.class));
                count("get", "hit", 1);
            } catch (JsonProcessingException e) {
                count("get", "error", 1);
                log.warn("상품 캐시 값을 읽지 못해 DB에서 조회한다: {}", e.getMessage());
            }
        }
        return found;
    }

    // 하나라도 저장에 실패하면 나머지도 멈춘다 — Redis가 응답하지 않을 때 상품마다 대기 시간을 다시 쓰지 않게
    @Override
    public void saveAll(Map<Long, Long> revisionsByProductId, List<ProductSnapshot> snapshots) {
        try {
            for (ProductSnapshot snapshot : snapshots) {
                String key = toRedisKey(snapshot.id(), revisionsByProductId.get(snapshot.id()));
                redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(snapshot), TTL);
            }
            count("put", "ok", snapshots.size());
        } catch (DataAccessException | JsonProcessingException e) {
            count("put", "error", 1);
            log.warn("상품 캐시 저장 실패, 조회 결과는 그대로 응답한다: {}", e.getMessage());
        }
    }

    static String toRedisKey(Long productId, Long revision) {
        return KEY_PREFIX + ":id=" + productId + ":rev=" + revision;
    }

    private void count(String operation, String result, int amount) {
        meterRegistry.counter(METRIC, "op", operation, "result", result).increment(amount);
    }
}
