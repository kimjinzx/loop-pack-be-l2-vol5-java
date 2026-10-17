package com.loopers.domain.product;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;

@RequiredArgsConstructor
@Component
public class ProductListRevisionService {

    private final ProductListRevisionRepository productListRevisionRepository;

    // 목록 결과를 바꾸는 쓰기 트랜잭션 안에서만 부른다. 다른 잠금을 모두 잡은 뒤 마지막에 부른다
    @Transactional(propagation = Propagation.MANDATORY)
    public void increase(Long brandId) {
        productListRevisionRepository.increase(brandId);
    }

    // 여러 브랜드는 브랜드 id 오름차순으로 잠근다 — 두 트랜잭션이 서로 반대 순서로 revision 행을 잡지 않게
    @Transactional(propagation = Propagation.MANDATORY)
    public void increaseAll(Collection<Long> brandIds) {
        brandIds.stream().distinct().sorted().forEach(productListRevisionRepository::increase);
    }

    // 브랜드 목록은 그 브랜드의 revision, 전체 목록은 모든 브랜드 revision의 합을 쓴다 — 어느 브랜드가 바뀌어도 합은 커진다
    @Transactional(readOnly = true)
    public long getRevision(Long brandId) {
        if (brandId == null) {
            return productListRevisionRepository.sumRevisions();
        }
        return productListRevisionRepository.findRevision(brandId);
    }
}
