package com.loopers.domain.point;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "user_point", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id"}))
public class PointModel extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "balance", nullable = false)
    private Long balance;

    protected PointModel() {
    }

    public PointModel(Long userId) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "사용자는 비어있을 수 없습니다.");
        }
        this.userId = userId;
        this.balance = 0L;
    }

    public void charge(Long amount) {
        if (amount == null || amount <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "충전 금액은 0보다 커야 합니다.");
        }
        this.balance += amount;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getBalance() {
        return balance;
    }
}
