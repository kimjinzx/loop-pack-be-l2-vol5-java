package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class BrandService {

    private final BrandRepository brandRepository;

    @Transactional(readOnly = true)
    public BrandModel getBrand(Long id) {
        return brandRepository.findActiveById(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 브랜드를 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public BrandModel getBrandForAdmin(Long id) {
        return brandRepository.findById(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 브랜드를 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public Page<BrandModel> getBrands(Pageable pageable) {
        return brandRepository.findAll(pageable);
    }

    @Transactional
    public BrandModel createBrand(String name) {
        return brandRepository.save(new BrandModel(name));
    }

    @Transactional
    public BrandModel updateBrand(Long id, String name) {
        BrandModel brand = getBrandForAdmin(id);
        brand.updateName(name);
        return brand;
    }

    @Transactional
    public void deleteBrand(Long id) {
        // TODO: 연결된 미삭제 상품이 있으면 삭제를 거절해야 한다 (상품 도메인 추가 시 application에서 확인 후 호출)
        BrandModel brand = getBrandForAdmin(id);
        brand.delete();
    }
}
