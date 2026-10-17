package com.loopers.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// 브랜드별 상품 목록의 revision을 정렬마다 따로 둔다. 목록 캐시 키가 이 값을 쓴다.
// 정렬마다 나누는 이유: 가격 수정은 가격순만, 좋아요는 좋아요순만 순서를 바꾸므로 다른 정렬의 캐시는 그대로 쓸 수 있다
@Entity
@Table(name = "product_list_revision")
public class ProductListRevisionModel {

    @Id
    @Column(name = "brand_id")
    private Long brandId;

    @Column(name = "latest_revision", nullable = false)
    private long latestRevision;

    @Column(name = "price_revision", nullable = false)
    private long priceRevision;

    @Column(name = "likes_revision", nullable = false)
    private long likesRevision;

    protected ProductListRevisionModel() {
    }

    public long revisionOf(ProductSortType sortType) {
        return switch (sortType) {
            case LATEST -> latestRevision;
            case PRICE_ASC -> priceRevision;
            case LIKES_DESC -> likesRevision;
        };
    }
}
