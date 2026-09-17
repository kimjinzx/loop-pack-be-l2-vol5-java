package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductJpaRepository extends JpaRepository<ProductModel, Long> {
    boolean existsByBrandIdAndDeletedAtIsNull(Long brandId);

    Page<ProductModel> findByDeletedAtIsNull(Pageable pageable);
}
