package com.loopers.application.product;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.like.LikeModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSortType;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
class ProductCacheIntegrationTest {

    @Autowired
    private ProductFacade productFacade;

    @MockitoSpyBean
    private ProductService productService;

    @MockitoSpyBean
    private ProductListCache productListCache;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private LikeJpaRepository likeJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private Long nikeId;
    private Long adidasId;

    @BeforeEach
    void setUp() {
        nikeId = brandJpaRepository.save(new BrandModel("나이키")).getId();
        adidasId = brandJpaRepository.save(new BrandModel("아디다스")).getId();
        Long runner = productJpaRepository.save(new ProductModel(nikeId, "runner", 30_000L, 5)).getId();
        Long slipper = productJpaRepository.save(new ProductModel(nikeId, "slipper", 10_000L, 3)).getId();
        productJpaRepository.save(new ProductModel(nikeId, "cap", 20_000L, 0));
        productJpaRepository.save(new ProductModel(adidasId, "jacket", 50_000L, 7));
        likeJpaRepository.save(new LikeModel(1L, runner));
        likeJpaRepository.save(new LikeModel(2L, runner));
        likeJpaRepository.save(new LikeModel(1L, slipper));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @DisplayName("같은 조건이면 DB 직접 조회·miss·hit의 결과가 같고, hit에서는 목록·건수·좋아요·브랜드 조회를 하지 않는다.")
    @Test
    void returnsSameResultForDbOnlyMissAndHit_andSkipsListQueriesOnHit() {
        // arrange — 캐시를 거치지 않는 DB 직접 조회 결과
        doReturn(Optional.empty()).when(productListCache).find(any());
        doNothing().when(productListCache).save(any(), any());
        Page<ProductInfo> dbOnly = getNikePriceAsc();
        reset(productListCache);
        clearInvocations(productService);
        double missesBefore = cacheCount("get", "miss");
        double hitsBefore = cacheCount("get", "hit");

        // act
        Page<ProductInfo> miss = getNikePriceAsc();
        Page<ProductInfo> hit = getNikePriceAsc();

        // assert
        assertAll(
            () -> assertThat(dbOnly.getContent()).extracting(ProductInfo::name).containsExactly("slipper", "cap", "runner"),
            () -> assertThat(dbOnly.getContent()).extracting(ProductInfo::likeCount).containsExactly(1L, 0L, 2L),
            () -> assertThat(miss.getContent()).isEqualTo(dbOnly.getContent()),
            () -> assertThat(hit.getContent()).isEqualTo(dbOnly.getContent()),
            () -> assertThat(hit.getTotalElements()).isEqualTo(dbOnly.getTotalElements()),
            () -> verify(productService, times(1)).getProducts(any(), any(), any()),
            () -> assertThat(cacheCount("get", "miss") - missesBefore).isEqualTo(1),
            () -> assertThat(cacheCount("get", "hit") - hitsBefore).isEqualTo(1)
        );
    }

    @DisplayName("브랜드·정렬·페이지·크기가 하나라도 다르면, 다른 캐시 항목을 쓴다.")
    @Test
    void usesSeparateEntries_whenAnyConditionDiffers() {
        // arrange
        Page<ProductInfo> firstPage = productFacade.getProducts(nikeId, ProductSortType.PRICE_ASC, PageRequest.of(0, 2));
        clearInvocations(productService);

        // act
        Page<ProductInfo> secondPage = productFacade.getProducts(nikeId, ProductSortType.PRICE_ASC, PageRequest.of(1, 2));
        Page<ProductInfo> otherSort = productFacade.getProducts(nikeId, ProductSortType.LIKES_DESC, PageRequest.of(0, 2));
        Page<ProductInfo> otherBrand = productFacade.getProducts(adidasId, ProductSortType.PRICE_ASC, PageRequest.of(0, 2));
        Page<ProductInfo> allBrands = productFacade.getProducts(null, ProductSortType.PRICE_ASC, PageRequest.of(0, 2));
        Page<ProductInfo> otherSize = productFacade.getProducts(nikeId, ProductSortType.PRICE_ASC, PageRequest.of(0, 3));

        // assert
        assertAll(
            () -> verify(productService, times(5)).getProducts(any(), any(), any()),
            () -> assertThat(names(firstPage)).containsExactly("slipper", "cap"),
            () -> assertThat(names(secondPage)).containsExactly("runner"),
            () -> assertThat(names(otherSort)).containsExactly("runner", "slipper"),
            () -> assertThat(names(otherBrand)).containsExactly("jacket"),
            () -> assertThat(names(allBrands)).containsExactly("slipper", "cap"),
            () -> assertThat(names(otherSize)).containsExactly("slipper", "cap", "runner")
        );
    }

    private Page<ProductInfo> getNikePriceAsc() {
        return productFacade.getProducts(nikeId, ProductSortType.PRICE_ASC, PageRequest.of(0, 20));
    }

    private List<String> names(Page<ProductInfo> page) {
        return page.getContent().stream().map(ProductInfo::name).toList();
    }

    private double cacheCount(String operation, String result) {
        return meterRegistry.counter("product.list.cache", "op", operation, "result", result).count();
    }
}
