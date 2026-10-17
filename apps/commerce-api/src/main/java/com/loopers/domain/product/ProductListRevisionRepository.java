package com.loopers.domain.product;

public interface ProductListRevisionRepository {
    // 행이 없으면 주어진 증가분으로 만들고, 있으면 정렬별로 더한다
    void increase(Long brandId, int latest, int price, int likes);

    // 행이 없으면 0
    long findRevision(Long brandId, ProductSortType sortType);

    long sumRevisions(ProductSortType sortType);
}
