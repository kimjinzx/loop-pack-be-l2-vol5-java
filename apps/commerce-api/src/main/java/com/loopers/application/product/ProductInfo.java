package com.loopers.application.product;

import com.loopers.domain.product.ProductModel;

public record ProductInfo(Long id, Long brandId, String name, Long price, int stock, long likeCount) {
    public static ProductInfo from(ProductModel model, long likeCount) {
        return new ProductInfo(
            model.getId(),
            model.getBrandId(),
            model.getName(),
            model.getPrice(),
            model.getStock(),
            likeCount
        );
    }
}
