package com.loopers.application.product;

import java.util.List;

// 목록 캐시에 담는 값. 응답의 공통 필드만 담고, 사용자별 정보는 없다
public record ProductListPage(List<ProductInfo> content, long totalElements) {
}
