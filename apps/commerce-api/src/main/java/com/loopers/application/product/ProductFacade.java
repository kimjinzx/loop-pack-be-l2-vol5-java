package com.loopers.application.product;

import com.loopers.domain.brand.BrandService;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSortType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class ProductFacade {
    private final ProductService productService;
    private final BrandService brandService;
    private final LikeService likeService;

    public ProductInfo getProduct(Long id) {
        ProductModel product = productService.getProduct(id);
        long likeCount = likeService.countActiveByProduct(id);
        return ProductInfo.from(product, likeCount);
    }

    public ProductAdminInfo getProductForAdmin(Long id) {
        ProductModel product = productService.getProductForAdmin(id);
        return ProductAdminInfo.from(product);
    }

    public Page<ProductInfo> getProducts(ProductSortType sortType, Pageable pageable) {
        Page<ProductModel> products = productService.getProducts(sortType, pageable);
        List<Long> productIds = products.getContent().stream().map(ProductModel::getId).toList();
        Map<Long, Long> likeCounts = likeService.countActiveByProducts(productIds);
        return products.map(product -> ProductInfo.from(product, likeCounts.getOrDefault(product.getId(), 0L)));
    }

    public Page<ProductAdminInfo> getProductsForAdmin(Pageable pageable) {
        return productService.getProductsForAdmin(pageable).map(ProductAdminInfo::from);
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
