package com.loopers.domain.product;

import org.springframework.data.domain.Sort;

// 정렬 값이 같은 상품은 id로 순서를 고정한다 — 동점 상품이 페이지 사이에서 중복·누락되지 않게
public enum ProductSortType {
    LATEST(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
    PRICE_ASC(Sort.by(Sort.Order.asc("price"), Sort.Order.asc("id"))),
    // TODO: 좋아요 기능 추가 후 좋아요 수 기준 정렬로 교체 (현재는 LATEST로 대체)
    LIKES_DESC(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

    private final Sort sort;

    ProductSortType(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}
