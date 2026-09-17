package com.loopers.domain.like;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface LikeRepository {
    LikeModel save(LikeModel like);

    Optional<LikeModel> findByUserIdAndProductId(Long userId, Long productId);

    long countActiveByProductId(Long productId);

    Map<Long, Long> countActiveByProductIds(List<Long> productIds);
}
