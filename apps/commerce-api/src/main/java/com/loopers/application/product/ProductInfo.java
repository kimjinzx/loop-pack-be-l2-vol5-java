package com.loopers.application.product;

import com.loopers.domain.product.ProductModel;

public record ProductInfo(Long id, Long brandId, String name, Long price, int stock, long likeCount) {
    public static ProductInfo from(ProductModel model) {
        // TODO: 좋아요 기능 추가 후 실제 좋아요 수로 교체
        return new ProductInfo(
            model.getId(),
            model.getBrandId(),
            model.getName(),
            model.getPrice(),
            model.getStock(),
            0L
        );
    }
}
