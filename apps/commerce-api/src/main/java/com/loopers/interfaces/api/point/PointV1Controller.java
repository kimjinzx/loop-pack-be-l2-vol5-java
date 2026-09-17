package com.loopers.interfaces.api.point;

import com.loopers.application.point.PointFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/points")
public class PointV1Controller {

    private final PointFacade pointFacade;

    // TODO: 사용자 식별 공통 처리 도입 후 X-USER-ID 헤더 직접 파싱을 공통 리졸버로 대체
    @GetMapping
    public ApiResponse<PointV1Dto.PointResponse> getPoint(@RequestHeader("X-USER-ID") Long userId) {
        var info = pointFacade.getPoint(userId);
        return ApiResponse.success(PointV1Dto.PointResponse.from(info));
    }

    @PostMapping("/charge")
    public ApiResponse<PointV1Dto.PointResponse> charge(
        @RequestHeader("X-USER-ID") Long userId,
        @RequestBody PointV1Dto.ChargeRequest request
    ) {
        var info = pointFacade.charge(userId, request.amount());
        return ApiResponse.success(PointV1Dto.PointResponse.from(info));
    }
}
