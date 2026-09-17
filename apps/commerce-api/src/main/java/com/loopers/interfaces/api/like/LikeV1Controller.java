package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/products/{productId}/likes")
public class LikeV1Controller {

    private final LikeFacade likeFacade;

    // TODO: 사용자 식별 공통 처리 도입 후 X-USER-ID 헤더 직접 파싱을 공통 리졸버로 대체
    @PostMapping
    public ApiResponse<Object> like(@PathVariable Long productId, @RequestHeader("X-USER-ID") Long userId) {
        likeFacade.like(userId, productId);
        return ApiResponse.success();
    }

    @DeleteMapping
    public ApiResponse<Object> unlike(@PathVariable Long productId, @RequestHeader("X-USER-ID") Long userId) {
        likeFacade.unlike(userId, productId);
        return ApiResponse.success();
    }
}
