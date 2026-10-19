package com.loopers.application.product;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.like.LikeFacade;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemCommand;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRevisionService;
import com.loopers.domain.product.ProductSortType;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

// 각 장면은 목록을 먼저 캐시에 올린 뒤 변경하고, 변경이 끝난 뒤 시작한 조회가 변경된 결과를 돌려주는지 본다
@SpringBootTest
class ProductCacheChangeIntegrationTest {

    private static final long BUYER_ID = 1L;
    private static final long POOR_BUYER_ID = 2L;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private BrandFacade brandFacade;

    @Autowired
    private LikeFacade likeFacade;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private ProductRevisionService productRevisionService;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private PointJpaRepository pointJpaRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private Long nikeId;
    private Long adidasId;
    private Long runnerId;
    private Long slipperId;
    private Long capId;

    @BeforeEach
    void setUp() {
        nikeId = brandJpaRepository.save(new BrandModel("나이키")).getId();
        adidasId = brandJpaRepository.save(new BrandModel("아디다스")).getId();
        runnerId = productJpaRepository.save(new ProductModel(nikeId, "runner", 30_000L, 5)).getId();
        slipperId = productJpaRepository.save(new ProductModel(nikeId, "slipper", 10_000L, 3)).getId();
        capId = productJpaRepository.save(new ProductModel(nikeId, "cap", 20_000L, 4)).getId();
        productJpaRepository.save(new ProductModel(adidasId, "jacket", 50_000L, 7));
        chargePoint(BUYER_ID, 1_000_000L);
        chargePoint(POOR_BUYER_ID, 1_000L);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @DisplayName("관리자가 가격을 수정하면, 이후 조회는 새 가격과 바뀐 가격순을 반환하고 과거 주문 금액은 그대로다.")
    @Test
    void reflectsNewPriceAndOrder_andKeepsPastOrderAmount_afterAdminPriceUpdate() {
        // arrange
        OrderInfo pastOrder = confirmOrder(slipperId, 1);
        assertThat(names(list(nikeId, ProductSortType.PRICE_ASC))).containsExactly("slipper", "cap", "runner");

        // act
        productFacade.updateProduct(runnerId, "runner", 5_000L);
        List<ProductInfo> after = list(nikeId, ProductSortType.PRICE_ASC);

        // assert
        OrderInfo pastOrderAfter = orderFacade.getOrder(pastOrder.id(), BUYER_ID);
        assertAll(
            () -> assertThat(names(after)).containsExactly("runner", "slipper", "cap"),
            () -> assertThat(after.get(0).price()).isEqualTo(5_000L),
            () -> assertThat(pastOrderAfter.totalAmount()).isEqualTo(10_000L),
            () -> assertThat(pastOrderAfter.items().get(0).unitPrice()).isEqualTo(10_000L)
        );
    }

    @DisplayName("관리자가 브랜드를 삭제하면, 그 브랜드 상품은 브랜드·전체 목록에서 빠지고 다른 브랜드와 과거 주문은 그대로다.")
    @Test
    void excludesProductsOfDeletedBrand_andKeepsOtherBrandAndPastOrder() {
        // arrange
        OrderInfo pastOrder = confirmOrder(runnerId, 1);
        assertThat(list(nikeId, ProductSortType.PRICE_ASC)).hasSize(3);
        assertThat(list(null, ProductSortType.PRICE_ASC)).hasSize(4);
        assertThat(names(list(adidasId, ProductSortType.PRICE_ASC))).containsExactly("jacket");

        // act
        brandFacade.deleteBrand(nikeId);

        // assert
        OrderInfo pastOrderAfter = orderFacade.getOrder(pastOrder.id(), BUYER_ID);
        assertAll(
            () -> assertThat(list(nikeId, ProductSortType.PRICE_ASC)).isEmpty(),
            () -> assertThat(names(list(null, ProductSortType.PRICE_ASC))).containsExactly("jacket"),
            () -> assertThat(names(list(adidasId, ProductSortType.PRICE_ASC))).containsExactly("jacket"),
            () -> assertThat(pastOrderAfter.items().get(0).productId()).isEqualTo(runnerId),
            () -> assertThat(pastOrderAfter.totalAmount()).isEqualTo(30_000L)
        );
    }

    @DisplayName("좋아요를 등록·취소하면, 이후 조회는 바뀐 좋아요 수와 좋아요순을 반환한다.")
    @Test
    void reflectsLikeCountAndOrder_afterLikeAndUnlike() {
        // arrange
        assertThat(names(list(nikeId, ProductSortType.LIKES_DESC))).containsExactly("runner", "slipper", "cap");

        // act & assert
        likeFacade.like(1L, capId);
        likeFacade.like(2L, capId);
        likeFacade.like(1L, slipperId);
        List<ProductInfo> afterLike = list(nikeId, ProductSortType.LIKES_DESC);
        assertThat(names(afterLike)).containsExactly("cap", "slipper", "runner");
        assertThat(afterLike).extracting(ProductInfo::likeCount).containsExactly(2L, 1L, 0L);

        likeFacade.unlike(1L, capId);
        likeFacade.unlike(2L, capId);
        List<ProductInfo> afterUnlike = list(nikeId, ProductSortType.LIKES_DESC);
        assertThat(names(afterUnlike)).containsExactly("slipper", "runner", "cap");
        assertThat(afterUnlike).extracting(ProductInfo::likeCount).containsExactly(1L, 0L, 0L);
    }

    @DisplayName("관리자가 재고를 바꾸거나 주문이 확정되면, 이후 조회는 바뀐 재고를 반환한다.")
    @Test
    void reflectsStock_afterAdminStockChangeAndOrderConfirm() {
        // arrange
        assertThat(stockOf(list(nikeId, ProductSortType.PRICE_ASC), "slipper")).isEqualTo(3);

        // act & assert
        productFacade.changeStock(slipperId, 10);
        assertThat(stockOf(list(nikeId, ProductSortType.PRICE_ASC), "slipper")).isEqualTo(10);

        confirmOrder(slipperId, 4);
        assertThat(stockOf(list(nikeId, ProductSortType.PRICE_ASC), "slipper")).isEqualTo(6);
    }

    @DisplayName("상품을 등록하거나 브랜드 이름을 바꾸면, 이후 조회는 새 상품과 바뀐 브랜드 이름을 반환한다.")
    @Test
    void reflectsNewProductAndBrandName_afterCreateAndBrandRename() {
        // arrange
        assertThat(list(nikeId, ProductSortType.PRICE_ASC)).hasSize(3);

        // act & assert
        productFacade.createProduct(nikeId, "sandal", 1_000L, 2);
        assertThat(names(list(nikeId, ProductSortType.PRICE_ASC))).containsExactly("sandal", "slipper", "cap", "runner");

        brandFacade.updateBrand(nikeId, "NIKE");
        assertThat(list(nikeId, ProductSortType.PRICE_ASC)).extracting(ProductInfo::brandName).containsOnly("NIKE");
    }

    @DisplayName("주문 확정이 잔액 부족으로 롤백되면, 상품 revision과 재고가 그대로라 캐시된 이전 결과가 DB와 같다.")
    @Test
    void keepsCachedResult_whenChangeIsRolledBack() {
        // arrange
        List<ProductInfo> before = list(nikeId, ProductSortType.PRICE_ASC);
        long revisionBefore = productRevision(runnerId);
        Long orderId = orderFacade.createOrder(POOR_BUYER_ID, List.of(new OrderItemCommand(runnerId, 1))).id();
        double listHitsBefore = cacheCount("product.list.cache");
        double productHitsBefore = cacheCount("product.cache");

        // act
        assertThrows(CoreException.class, () -> orderFacade.confirmOrder(orderId, POOR_BUYER_ID));
        List<ProductInfo> after = list(nikeId, ProductSortType.PRICE_ASC);

        // assert
        assertAll(
            () -> assertThat(productRevision(runnerId)).isEqualTo(revisionBefore),
            () -> assertThat(productJpaRepository.findById(runnerId).orElseThrow().getStock()).isEqualTo(5),
            () -> assertThat(after).isEqualTo(before),
            () -> assertThat(cacheCount("product.list.cache") - listHitsBefore).isEqualTo(1),
            () -> assertThat(cacheCount("product.cache") - productHitsBefore).as("상품 3개 모두 이전 캐시를 그대로 쓴다").isEqualTo(3)
        );
    }

    private List<ProductInfo> list(Long brandId, ProductSortType sortType) {
        return productFacade.getProducts(brandId, sortType, PageRequest.of(0, 20)).getContent();
    }

    private List<String> names(List<ProductInfo> products) {
        return products.stream().map(ProductInfo::name).toList();
    }

    private int stockOf(List<ProductInfo> products, String name) {
        return products.stream().filter(product -> product.name().equals(name)).findFirst().orElseThrow().stock();
    }

    private OrderInfo confirmOrder(Long productId, int quantity) {
        Long orderId = orderFacade.createOrder(BUYER_ID, List.of(new OrderItemCommand(productId, quantity))).id();
        return orderFacade.confirmOrder(orderId, BUYER_ID);
    }

    private void chargePoint(long userId, long amount) {
        PointModel point = new PointModel(userId);
        point.charge(amount);
        pointJpaRepository.save(point);
    }

    private long productRevision(Long productId) {
        return productRevisionService.getRevisions(List.of(productId)).get(productId);
    }

    private double cacheCount(String metric) {
        return meterRegistry.counter(metric, "op", "get", "result", "hit").count();
    }
}
