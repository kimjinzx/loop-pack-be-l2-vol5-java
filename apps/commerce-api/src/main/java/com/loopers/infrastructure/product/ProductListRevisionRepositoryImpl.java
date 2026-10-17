package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductListRevisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class ProductListRevisionRepositoryImpl implements ProductListRevisionRepository {
    private final ProductListRevisionJpaRepository productListRevisionJpaRepository;

    @Override
    public void increase(Long brandId) {
        productListRevisionJpaRepository.increase(brandId);
    }

    @Override
    public long findRevision(Long brandId) {
        return productListRevisionJpaRepository.findRevisionByBrandId(brandId).orElse(0L);
    }

    @Override
    public long sumRevisions() {
        return productListRevisionJpaRepository.sumRevisions();
    }
}
