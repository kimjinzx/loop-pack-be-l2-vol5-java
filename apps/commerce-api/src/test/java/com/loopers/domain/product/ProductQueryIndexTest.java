package com.loopers.domain.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductQueryIndexTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DisplayName("상품 목록·건수 조회용 인덱스는 브랜드, 삭제 여부, 가격, id 순서의 컬럼으로 만들어진다.")
    @Test
    void createsProductListIndexInBrandDeletedPriceIdOrder() {
        // act
        List<String> columns = indexColumns("product", "idx_product_brand_deleted_price");

        // assert
        assertThat(columns).containsExactly("brand_id", "deleted_at", "price", "id");
    }

    private List<String> indexColumns(String table, String index) {
        return jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ? ORDER BY seq_in_index",
            String.class, table, index
        );
    }
}
