package com.restaurant.entity.enums;

/**
 * Enum định nghĩa trạng thái món ăn trong đơn hàng
 */
public enum OrderItemStatus {
    PENDING,    // Chờ xử lý
    PREPARING,  // Đang chuẩn bị
    READY,      // Sẵn sàng
    SERVED      // Đã phục vụ
}
