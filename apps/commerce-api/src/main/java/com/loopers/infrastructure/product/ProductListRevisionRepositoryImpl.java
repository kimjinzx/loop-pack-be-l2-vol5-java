package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductListRevisionRepository;
import com.loopers.domain.product.ProductSortType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class ProductListRevisionRepositoryImpl implements ProductListRevisionRepository {
    private final ProductListRevisionJpaRepository productListRevisionJpaRepository;

    @Override
    public void increase(Long brandId, int latest, int price, int likes) {
        productListRevisionJpaRepository.increase(brandId, latest, price, likes);
    }

    @Override
    public long findRevision(Long brandId, ProductSortType sortType) {
        return productListRevisionJpaRepository.findById(brandId)
            .map(revision -> revision.revisionOf(sortType))
            .orElse(0L);
    }

    @Override
    public long sumRevisions(ProductSortType sortType) {
        return switch (sortType) {
            case LATEST -> productListRevisionJpaRepository.sumLatestRevisions();
            case PRICE_ASC -> productListRevisionJpaRepository.sumPriceRevisions();
            case LIKES_DESC -> productListRevisionJpaRepository.sumLikesRevisions();
        };
    }
}
