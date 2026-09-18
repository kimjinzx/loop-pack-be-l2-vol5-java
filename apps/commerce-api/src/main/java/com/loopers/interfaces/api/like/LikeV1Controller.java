package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.product.ProductV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
public class LikeV1Controller {

    private final LikeFacade likeFacade;

    // TODO: 사용자 식별 공통 처리 도입 후 X-USER-ID 헤더 직접 파싱을 공통 리졸버로 대체
    @PostMapping("/api/v1/products/{productId}/likes")
    public ApiResponse<Object> like(@PathVariable Long productId, @RequestHeader("X-USER-ID") Long userId) {
        likeFacade.like(userId, productId);
        return ApiResponse.success();
    }

    @DeleteMapping("/api/v1/products/{productId}/likes")
    public ApiResponse<Object> unlike(@PathVariable Long productId, @RequestHeader("X-USER-ID") Long userId) {
        likeFacade.unlike(userId, productId);
        return ApiResponse.success();
    }

    // 정식 사용자 식별 전까지는 {userId} 경로 대신 "요청 헤더의 나"만 조회 가능하도록 범위를 좁힘
    @GetMapping("/api/v1/likes")
    public ApiResponse<List<ProductV1Dto.ProductResponse>> getMyLikedProducts(
        @RequestHeader("X-USER-ID") Long userId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        var products = likeFacade.getMyLikedProducts(userId, PageRequest.of(page, size));
        return ApiResponse.success(products.map(ProductV1Dto.ProductResponse::from).getContent());
    }
}
