package com.loopers.domain.product;

import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductQueryIntegrationTest {

    private static final long BRAND_ID = 1L;
    private static final int SAME_VALUE_COUNT = 30;
    private static final int PAGE_SIZE = 10;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("가격 오름차순으로 목록을 조회할 때, ")
    @Nested
    class PriceAsc {
        @DisplayName("가격이 같은 상품은 id 오름차순으로 반환한다.")
        @Test
        void returnsSamePriceProductsInIdAscendingOrder() {
            // arrange
            Long expensive = saveProduct(2_000L);
            Long sameFirst = saveProduct(1_000L);
            Long cheap = saveProduct(500L);
            Long sameSecond = saveProduct(1_000L);
            Long sameThird = saveProduct(1_000L);

            // act
            List<Long> ids = getProductIds(ProductSortType.PRICE_ASC, 0, 5);

            // assert
            assertThat(ids).containsExactly(cheap, sameFirst, sameSecond, sameThird, expensive);
        }

        @DisplayName("가격이 같은 상품이 여러 페이지에 걸쳐도, 페이지 사이에 중복이나 누락 없이 id 오름차순으로 이어진다.")
        @Test
        void returnsSamePriceProductsAcrossPagesWithoutDuplicatesOrGaps() {
            // arrange
            List<Long> savedIds = new ArrayList<>();
            for (int i = 0; i < SAME_VALUE_COUNT; i++) {
                savedIds.add(saveProduct(5_900L));
            }

            // act
            List<Long> pagedIds = getAllPages(ProductSortType.PRICE_ASC);

            // assert
            assertThat(pagedIds).containsExactlyElementsOf(sorted(savedIds, Comparator.naturalOrder()));
        }
    }

    @DisplayName("최신순으로 목록을 조회할 때, ")
    @Nested
    class Latest {
        @DisplayName("등록 시각이 같은 상품이 여러 페이지에 걸쳐도, 페이지 사이에 중복이나 누락 없이 id 내림차순으로 이어진다.")
        @Test
        void returnsSameCreatedAtProductsAcrossPagesWithoutDuplicatesOrGaps() {
            // arrange
            List<Long> savedIds = new ArrayList<>();
            for (int i = 0; i < SAME_VALUE_COUNT; i++) {
                savedIds.add(saveProduct(5_900L + i));
            }
            jdbcTemplate.update("UPDATE product SET created_at = '2026-01-01 00:00:00' WHERE brand_id = ?", BRAND_ID);

            // act
            List<Long> pagedIds = getAllPages(ProductSortType.LATEST);

            // assert
            assertThat(pagedIds).containsExactlyElementsOf(sorted(savedIds, Comparator.reverseOrder()));
        }
    }

    private Long saveProduct(Long price) {
        return productJpaRepository.save(new ProductModel(BRAND_ID, "product", price, 10)).getId();
    }

    private List<Long> getProductIds(ProductSortType sortType, int page, int size) {
        return productService.getProducts(BRAND_ID, sortType, PageRequest.of(page, size)).getContent().stream()
            .map(ProductModel::getId)
            .toList();
    }

    private List<Long> getAllPages(ProductSortType sortType) {
        List<Long> ids = new ArrayList<>();
        for (int page = 0; page < SAME_VALUE_COUNT / PAGE_SIZE; page++) {
            ids.addAll(getProductIds(sortType, page, PAGE_SIZE));
        }
        return ids;
    }

    private List<Long> sorted(List<Long> ids, Comparator<Long> order) {
        return ids.stream().sorted(order).toList();
    }
}
