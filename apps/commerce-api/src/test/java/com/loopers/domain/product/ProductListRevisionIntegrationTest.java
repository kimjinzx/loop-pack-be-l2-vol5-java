package com.loopers.domain.product;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.like.LikeFacade;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.point.PointModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class ProductListRevisionIntegrationTest {

    private static final long USER_ID = 1L;

    @Autowired
    private ProductListRevisionService productListRevisionService;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private BrandFacade brandFacade;

    @Autowired
    private LikeFacade likeFacade;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private PointJpaRepository pointJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private Long brandId;
    private Long otherBrandId;
    private Long productId;
    private Long otherBrandProductId;

    @BeforeEach
    void setUp() {
        brandId = brandJpaRepository.save(new BrandModel("나이키")).getId();
        otherBrandId = brandJpaRepository.save(new BrandModel("아디다스")).getId();
        productId = productJpaRepository.save(new ProductModel(brandId, "runner", 10_000L, 5)).getId();
        otherBrandProductId = productJpaRepository.save(new ProductModel(otherBrandId, "slipper", 5_000L, 5)).getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("상품을 바꾸면, ")
    @Nested
    class ProductChange {
        @DisplayName("등록·수정·재고 변경·삭제마다 그 상품 브랜드의 revision이 1씩 오르고, 다른 브랜드는 그대로다.")
        @Test
        void increasesRevisionOfProductBrand() {
            // act & assert
            productFacade.createProduct(brandId, "new", 1_000L, 1);
            assertThat(revisionOf(brandId)).isEqualTo(1L);

            productFacade.updateProduct(productId, "runner", 12_000L);
            assertThat(revisionOf(brandId)).isEqualTo(2L);

            productFacade.changeStock(productId, 10);
            assertThat(revisionOf(brandId)).isEqualTo(3L);

            productFacade.deleteProduct(productId);
            assertThat(revisionOf(brandId)).isEqualTo(4L);

            assertThat(revisionOf(otherBrandId)).isZero();
        }
    }

    @DisplayName("브랜드를 바꾸면, ")
    @Nested
    class BrandChange {
        @DisplayName("이름 변경과 삭제마다 그 브랜드의 revision이 1씩 오른다.")
        @Test
        void increasesRevisionOfBrand() {
            // act & assert
            brandFacade.updateBrand(brandId, "NIKE");
            assertThat(revisionOf(brandId)).isEqualTo(1L);

            brandFacade.deleteBrand(brandId);
            assertThat(revisionOf(brandId)).isEqualTo(2L);
            assertThat(revisionOf(otherBrandId)).isZero();
        }
    }

    @DisplayName("좋아요를 바꾸면, ")
    @Nested
    class LikeChange {
        @DisplayName("등록과 취소마다 그 상품 브랜드의 revision이 1씩 오른다.")
        @Test
        void increasesRevisionOfProductBrand() {
            // act & assert
            likeFacade.like(USER_ID, productId);
            assertThat(revisionOf(brandId)).isEqualTo(1L);

            likeFacade.unlike(USER_ID, productId);
            assertThat(revisionOf(brandId)).isEqualTo(2L);
        }

        @DisplayName("삭제된 상품의 좋아요를 취소하면, 목록에 나가지 않는 상품이라 revision을 올리지 않는다.")
        @Test
        void keepsRevision_whenProductIsDeleted() {
            // arrange
            likeFacade.like(USER_ID, productId);
            productFacade.deleteProduct(productId);
            long before = revisionOf(brandId);

            // act
            likeFacade.unlike(USER_ID, productId);

            // assert
            assertThat(revisionOf(brandId)).isEqualTo(before);
        }
    }

    @DisplayName("주문을 확정하면, ")
    @Nested
    class OrderConfirm {
        @DisplayName("재고가 줄어든 상품들의 브랜드 revision이 각각 1씩 오른다.")
        @Test
        void increasesRevisionOfEachItemBrand() {
            // arrange
            chargePoint(100_000L);
            Long orderId = saveDraftOrder();

            // act
            orderFacade.confirmOrder(orderId, USER_ID);

            // assert
            assertThat(revisionOf(brandId)).isEqualTo(1L);
            assertThat(revisionOf(otherBrandId)).isEqualTo(1L);
        }

        @DisplayName("잔액 부족으로 확정이 롤백되면, revision도 그대로다.")
        @Test
        void keepsRevision_whenConfirmIsRolledBack() {
            // arrange
            chargePoint(1_000L);
            Long orderId = saveDraftOrder();

            // act
            assertThrows(CoreException.class, () -> orderFacade.confirmOrder(orderId, USER_ID));

            // assert
            assertThat(revisionOf(brandId)).isZero();
            assertThat(revisionOf(otherBrandId)).isZero();
        }
    }

    @DisplayName("브랜드를 지정하지 않은 전체 목록의 revision은, 어느 브랜드가 바뀌어도 커진다.")
    @Test
    void increasesAllProductsRevision_whenAnyBrandChanges() {
        // arrange
        long before = revisionOf(null);

        // act
        productFacade.updateProduct(otherBrandProductId, "slipper", 6_000L);

        // assert
        assertThat(revisionOf(null)).isGreaterThan(before);
    }

    @DisplayName("쓰기 트랜잭션 밖에서 revision을 올리려 하면, 변경과 같은 트랜잭션이 아니므로 거절한다.")
    @Test
    void rejectsIncrease_withoutTransaction() {
        // act & assert
        assertThrows(IllegalTransactionStateException.class, () -> productListRevisionService.increase(brandId));
    }

    private long revisionOf(Long brandId) {
        return productListRevisionService.getRevision(brandId);
    }

    private void chargePoint(long amount) {
        PointModel point = new PointModel(USER_ID);
        point.charge(amount);
        pointJpaRepository.save(point);
    }

    private Long saveDraftOrder() {
        OrderModel draft = new OrderModel(USER_ID, List.of(
            new OrderItem(productId, 1, 10_000L),
            new OrderItem(otherBrandProductId, 1, 5_000L)
        ));
        return orderJpaRepository.save(draft).getId();
    }
}
