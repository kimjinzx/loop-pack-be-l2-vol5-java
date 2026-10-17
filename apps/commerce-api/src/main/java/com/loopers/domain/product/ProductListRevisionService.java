package com.loopers.domain.product;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 목록 결과를 바꾸는 쓰기 트랜잭션 안에서만 올린다(MANDATORY). 다른 잠금을 모두 잡은 뒤 마지막에 부른다
@RequiredArgsConstructor
@Component
public class ProductListRevisionService {

    private final ProductListRevisionRepository productListRevisionRepository;

    // 목록에 들어가고 빠지는 상품이 바뀐다(등록·삭제·브랜드 삭제) — 모든 정렬의 목록이 바뀐다
    @Transactional(propagation = Propagation.MANDATORY)
    public void increaseForMembership(Long brandId) {
        productListRevisionRepository.increase(brandId, 1, 1, 1);
    }

    // 가격이 바뀐다 — 가격순 목록의 순서만 바뀐다
    @Transactional(propagation = Propagation.MANDATORY)
    public void increaseForPrice(Long brandId) {
        productListRevisionRepository.increase(brandId, 0, 1, 0);
    }

    // 좋아요 수가 바뀐다 — 좋아요순 목록의 순서만 바뀐다
    @Transactional(propagation = Propagation.MANDATORY)
    public void increaseForLikes(Long brandId) {
        productListRevisionRepository.increase(brandId, 0, 0, 1);
    }

    // 브랜드 목록은 그 브랜드의 revision, 전체 목록은 모든 브랜드 revision의 합을 쓴다 — 어느 브랜드가 바뀌어도 합은 커진다
    @Transactional(readOnly = true)
    public long getRevision(Long brandId, ProductSortType sortType) {
        if (brandId == null) {
            return productListRevisionRepository.sumRevisions(sortType);
        }
        return productListRevisionRepository.findRevision(brandId, sortType);
    }
}
