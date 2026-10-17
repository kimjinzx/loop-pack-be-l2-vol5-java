package com.loopers.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// 브랜드별 상품 목록의 revision. 목록 결과를 바꾸는 쓰기가 같은 트랜잭션에서 1씩 올리고, 목록 캐시 키가 이 값을 쓴다
@Entity
@Table(name = "product_list_revision")
public class ProductListRevisionModel {

    @Id
    @Column(name = "brand_id")
    private Long brandId;

    @Column(name = "revision", nullable = false)
    private long revision;

    protected ProductListRevisionModel() {
    }
}
