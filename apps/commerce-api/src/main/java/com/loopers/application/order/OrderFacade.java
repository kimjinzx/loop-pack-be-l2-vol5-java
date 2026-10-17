package com.loopers.application.order;

import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.point.PointService;
import com.loopers.domain.product.ProductListRevisionService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class OrderFacade {
    private final OrderService orderService;
    private final ProductService productService;
    private final PointService pointService;
    private final ProductListRevisionService productListRevisionService;

    // 주문 품목(items)은 지연 로딩 컬렉션이라, 조회와 Info 매핑이 같은 트랜잭션(세션) 안에서 끝나야 한다.
    @Transactional(readOnly = true)
    public OrderInfo getOrder(Long id, Long userId) {
        return OrderInfo.from(orderService.getOrder(id, userId));
    }

    @Transactional(readOnly = true)
    public Page<OrderInfo> getOrders(Long userId, Pageable pageable) {
        return orderService.getOrders(userId, pageable).map(OrderInfo::from);
    }

    @Transactional(readOnly = true)
    public OrderAdminInfo getOrderForAdmin(Long id) {
        return OrderAdminInfo.from(orderService.getOrderForAdmin(id));
    }

    @Transactional(readOnly = true)
    public Page<OrderAdminInfo> getOrdersForAdmin(Pageable pageable) {
        return orderService.getOrdersForAdmin(pageable).map(OrderAdminInfo::from);
    }

    @Transactional
    public OrderInfo createOrder(Long userId, List<OrderItemCommand> requestItems) {
        if (requestItems == null || requestItems.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 품목은 비어있을 수 없습니다.");
        }
        for (OrderItemCommand item : requestItems) {
            if (item.quantity() <= 0) {
                throw new CoreException(ErrorType.BAD_REQUEST, "수량은 0보다 커야 합니다.");
            }
        }

        Map<Long, Integer> mergedQuantities = requestItems.stream()
            .collect(Collectors.groupingBy(OrderItemCommand::productId, Collectors.summingInt(OrderItemCommand::quantity)));

        List<OrderItem> items = mergedQuantities.entrySet().stream()
            .map(entry -> {
                ProductModel product = productService.getProduct(entry.getKey());
                return new OrderItem(entry.getKey(), entry.getValue(), product.getPrice());
            })
            .toList();

        OrderModel order = orderService.createOrder(userId, items);
        return OrderInfo.from(order);
    }

    @Transactional
    public OrderInfo confirmOrder(Long orderId, Long userId) {
        OrderModel order = orderService.getDraftOrderOwnedBy(orderId, userId);

        // 상품은 id 오름차순으로 잠근다
        List<OrderItem> items = order.getItems().stream()
            .sorted(Comparator.comparing(OrderItem::getProductId))
            .toList();
        List<Long> brandIds = new ArrayList<>();
        for (OrderItem item : items) {
            brandIds.add(productService.decreaseStock(item.getProductId(), item.getQuantity()).getBrandId());
        }

        pointService.pay(userId, order.getTotalAmount());
        orderService.confirmOrder(order, order.getTotalAmount());

        // 재고가 바뀐 상품의 목록 revision은 다른 잠금을 모두 잡은 뒤 마지막에 올린다
        productListRevisionService.increaseAll(brandIds);

        return OrderInfo.from(order);
    }
}
