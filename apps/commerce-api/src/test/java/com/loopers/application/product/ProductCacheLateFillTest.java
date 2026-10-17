package com.loopers.application.product;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductListRevisionService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductSortType;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.RedisProductListCache;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

// 이전 조회가 DB를 읽은 뒤 캐시에 저장하기 전에 관리자 변경을 끼워 넣어, 늦은 캐시 채움을 결정적으로 재현한다
@SpringBootTest
@Import(ProductCacheLateFillTest.PausingCacheConfig.class)
class ProductCacheLateFillTest {

    private static final long TIMEOUT_SECONDS = 10;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private PausingProductListCache pausingCache;

    @Autowired
    private RedisProductListCache redisProductListCache;

    @Autowired
    private ProductListRevisionService productListRevisionService;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private ExecutorService previousReader;
    private Long nikeId;
    private Long runnerId;

    @BeforeEach
    void setUp() {
        nikeId = brandJpaRepository.save(new BrandModel("나이키")).getId();
        runnerId = productJpaRepository.save(new ProductModel(nikeId, "runner", 30_000L, 5)).getId();
        productJpaRepository.save(new ProductModel(nikeId, "slipper", 10_000L, 3));
        previousReader = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pausingCache.resume();
        previousReader.shutdownNow();
        previousReader.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @DisplayName("이전 조회가 가격 수정 전 결과를 늦게 캐시에 저장해도, 수정이 끝난 뒤 시작한 조회는 새 가격을 반환한다.")
    @Test
    void returnsNewPrice_evenIfPreviousReadFillsCacheLate() throws Exception {
        // arrange — 이전 조회가 DB에서 옛 가격을 읽고 저장 직전에 멈춘다
        long revisionBefore = productListRevisionService.getRevision(nikeId);
        Future<List<ProductInfo>> previousRead = startPreviousReadPausedBeforeSave();

        // act — 관리자 변경 commit → 이전 조회가 옛 결과를 저장 → 새 조회
        productFacade.updateProduct(runnerId, "runner", 5_000L);
        pausingCache.resume();
        List<ProductInfo> previousResult = previousRead.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        List<ProductInfo> newResult = list();

        // assert
        assertAll(
            () -> assertThat(priceOf(previousResult, "runner")).as("이전 조회는 자신이 읽은 시점의 결과").isEqualTo(30_000L),
            () -> assertThat(priceOf(cachedAt(revisionBefore), "runner")).as("옛 결과는 옛 revision 키에 늦게 저장됐다").isEqualTo(30_000L),
            () -> assertThat(priceOf(newResult, "runner")).as("새 조회는 새 revision 키를 써서 새 가격").isEqualTo(5_000L),
            () -> assertThat(priceOf(list(), "runner")).as("새 revision 키에 채워진 값도 새 가격").isEqualTo(5_000L)
        );
    }

    @DisplayName("이전 조회가 삭제 전 목록을 늦게 캐시에 저장해도, 삭제가 끝난 뒤 시작한 조회에는 삭제된 상품이 나오지 않는다.")
    @Test
    void excludesDeletedProduct_evenIfPreviousReadFillsCacheLate() throws Exception {
        // arrange
        long revisionBefore = productListRevisionService.getRevision(nikeId);
        Future<List<ProductInfo>> previousRead = startPreviousReadPausedBeforeSave();

        // act
        productFacade.deleteProduct(runnerId);
        pausingCache.resume();
        previousRead.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        List<ProductInfo> newResult = list();

        // assert
        assertAll(
            () -> assertThat(names(cachedAt(revisionBefore))).as("옛 revision 키에는 삭제 전 목록").contains("runner"),
            () -> assertThat(names(newResult)).containsExactly("slipper"),
            () -> assertThat(names(list())).containsExactly("slipper")
        );
    }

    private Future<List<ProductInfo>> startPreviousReadPausedBeforeSave() throws InterruptedException {
        pausingCache.pauseNextSave();
        Future<List<ProductInfo>> previousRead = previousReader.submit(this::list);
        assertThat(pausingCache.awaitPaused(TIMEOUT_SECONDS)).as("이전 조회가 DB를 읽고 저장 직전에 멈춤").isTrue();
        return previousRead;
    }

    private List<ProductInfo> list() {
        return productFacade.getProducts(nikeId, ProductSortType.PRICE_ASC, PageRequest.of(0, 20)).getContent();
    }

    private List<ProductInfo> cachedAt(long revision) {
        ProductListCacheKey key = new ProductListCacheKey(nikeId, revision, ProductSortType.PRICE_ASC, 0, 20);
        return redisProductListCache.find(key).map(ProductListPage::content).orElseThrow();
    }

    private Long priceOf(List<ProductInfo> products, String name) {
        return products.stream().filter(product -> product.name().equals(name)).findFirst().orElseThrow().price();
    }

    private List<String> names(List<ProductInfo> products) {
        return products.stream().map(ProductInfo::name).toList();
    }

    @TestConfiguration
    static class PausingCacheConfig {
        @Bean
        @Primary
        PausingProductListCache pausingProductListCache(RedisProductListCache redisProductListCache) {
            return new PausingProductListCache(redisProductListCache);
        }
    }

    // 다음 저장 한 번을 멈춰 두었다가 resume()에서 이어서 저장한다. 조회는 그대로 Redis에 위임한다
    static class PausingProductListCache implements ProductListCache {
        private final ProductListCache delegate;
        private volatile CountDownLatch paused;
        private volatile CountDownLatch resumed;

        PausingProductListCache(ProductListCache delegate) {
            this.delegate = delegate;
        }

        void pauseNextSave() {
            resumed = new CountDownLatch(1);
            paused = new CountDownLatch(1);
        }

        boolean awaitPaused(long timeoutSeconds) throws InterruptedException {
            return paused.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        void resume() {
            CountDownLatch latch = resumed;
            if (latch != null) {
                latch.countDown();
            }
        }

        @Override
        public Optional<ProductListPage> find(ProductListCacheKey key) {
            return delegate.find(key);
        }

        @Override
        public void save(ProductListCacheKey key, ProductListPage page) {
            CountDownLatch pausedLatch = paused;
            CountDownLatch resumedLatch = resumed;
            if (pausedLatch != null && pausedLatch.getCount() > 0) {
                pausedLatch.countDown();
                try {
                    resumedLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            delegate.save(key, page);
        }
    }
}
