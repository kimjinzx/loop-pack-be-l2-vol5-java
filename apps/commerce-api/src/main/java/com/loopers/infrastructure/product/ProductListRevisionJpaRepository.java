package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductListRevisionModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductListRevisionJpaRepository extends JpaRepository<ProductListRevisionModel, Long> {

    @Modifying
    @Query(
        value = "INSERT INTO product_list_revision (brand_id, latest_revision, price_revision, likes_revision) "
            + "VALUES (:brandId, :latest, :price, :likes) "
            + "ON DUPLICATE KEY UPDATE latest_revision = latest_revision + :latest, "
            + "price_revision = price_revision + :price, likes_revision = likes_revision + :likes",
        nativeQuery = true
    )
    void increase(
        @Param("brandId") Long brandId,
        @Param("latest") int latest,
        @Param("price") int price,
        @Param("likes") int likes
    );

    @Query("select coalesce(sum(r.latestRevision), 0) from ProductListRevisionModel r")
    long sumLatestRevisions();

    @Query("select coalesce(sum(r.priceRevision), 0) from ProductListRevisionModel r")
    long sumPriceRevisions();

    @Query("select coalesce(sum(r.likesRevision), 0) from ProductListRevisionModel r")
    long sumLikesRevisions();
}
