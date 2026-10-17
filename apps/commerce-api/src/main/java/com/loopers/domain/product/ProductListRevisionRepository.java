package com.loopers.domain.product;

public interface ProductListRevisionRepository {
    // 행이 없으면 1로 만들고, 있으면 1 올린다
    void increase(Long brandId);

    // 행이 없으면 0
    long findRevision(Long brandId);

    long sumRevisions();
}
