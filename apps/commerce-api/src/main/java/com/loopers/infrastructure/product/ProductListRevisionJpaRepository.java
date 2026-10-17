package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductListRevisionModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductListRevisionJpaRepository extends JpaRepository<ProductListRevisionModel, Long> {

    @Modifying
    @Query(
        value = "INSERT INTO product_list_revision (brand_id, revision) VALUES (:brandId, 1) "
            + "ON DUPLICATE KEY UPDATE revision = revision + 1",
        nativeQuery = true
    )
    void increase(@Param("brandId") Long brandId);

    @Query("select r.revision from ProductListRevisionModel r where r.brandId = :brandId")
    Optional<Long> findRevisionByBrandId(@Param("brandId") Long brandId);

    @Query("select coalesce(sum(r.revision), 0) from ProductListRevisionModel r")
    long sumRevisions();
}
