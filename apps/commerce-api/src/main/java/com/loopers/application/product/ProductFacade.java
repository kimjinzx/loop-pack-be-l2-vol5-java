package com.loopers.application.product;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductListRevisionService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSortType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@RequiredArgsConstructor
@Component
public class ProductFacade {
    private final ProductService productService;
    private final BrandService brandService;
    private final LikeService likeService;
    private final ProductListRevisionService productListRevisionService;
    private final ProductListCache productListCache;

    public ProductInfo getProduct(Long id) {
        ProductModel product = productService.getProduct(id);
        BrandModel brand = brandService.getBrand(product.getBrandId());
        long likeCount = likeService.countActiveByProduct(id);
        return ProductInfo.from(product, brand.getName(), likeCount);
    }

    public ProductAdminInfo getProductForAdmin(Long id) {
        ProductModel product = productService.getProductForAdmin(id);
        return ProductAdminInfo.from(product);
    }

    // revision과 목록을 같은 읽기 트랜잭션(같은 스냅숏)에서 읽는다 — 이전 결과가 새 revision의 키에 저장되지 않게
    @Transactional(readOnly = true)
    public Page<ProductInfo> getProducts(Long brandId, ProductSortType sortType, Pageable pageable) {
        long revision = productListRevisionService.getRevision(brandId);
        ProductListCacheKey key = new ProductListCacheKey(
            brandId, revision, sortType, pageable.getPageNumber(), pageable.getPageSize()
        );
        Optional<ProductListPage> cached = productListCache.find(key);
        if (cached.isPresent()) {
            return new PageImpl<>(cached.get().content(), pageable, cached.get().totalElements());
        }

        Page<ProductModel> products = productService.getProducts(brandId, sortType, pageable);
        Page<ProductInfo> result = products.map(toProductInfo(products.getContent()));
        productListCache.save(key, new ProductListPage(result.getContent(), result.getTotalElements()));
        return result;
    }

    public Page<ProductAdminInfo> getProductsForAdmin(Pageable pageable) {
        return productService.getProductsForAdmin(pageable).map(ProductAdminInfo::from);
    }

    public List<ProductInfo> getProductsByIds(List<Long> productIds) {
        List<ProductModel> products = productService.getActiveProducts(productIds);
        return products.stream().map(toProductInfo(products)).toList();
    }

    private Function<ProductModel, ProductInfo> toProductInfo(List<ProductModel> products) {
        List<Long> productIds = products.stream().map(ProductModel::getId).toList();
        List<Long> brandIds = products.stream().map(ProductModel::getBrandId).distinct().toList();
        Map<Long, Long> likeCounts = likeService.countActiveByProducts(productIds);
        Map<Long, BrandModel> brands = brandService.getBrandsByIds(brandIds);
        return product -> ProductInfo.from(
            product,
            brands.get(product.getBrandId()).getName(),
            likeCounts.getOrDefault(product.getId(), 0L)
        );
    }

    public ProductAdminInfo createProduct(Long brandId, String name, Long price, int stock) {
        brandService.getBrand(brandId);
        ProductModel product = productService.createProduct(brandId, name, price, stock);
        return ProductAdminInfo.from(product);
    }

    public ProductAdminInfo updateProduct(Long id, String name, Long price) {
        ProductModel product = productService.updateProduct(id, name, price);
        return ProductAdminInfo.from(product);
    }

    public ProductAdminInfo changeStock(Long id, int quantity) {
        ProductModel product = productService.changeStock(id, quantity);
        return ProductAdminInfo.from(product);
    }

    public void deleteProduct(Long id) {
        productService.deleteProduct(id);
    }
}
