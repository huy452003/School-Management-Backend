package com.restaurant.entity.enums;

/**
 * Enum định nghĩa trạng thái đơn hàng
 */
public enum OrderStatus {
    PENDING,    // Chờ xử lý
    CONFIRMED,  // Đã xác nhận
    PREPARING,  // Đang chuẩn bị
    READY,      // Sẵn sàng phục vụ
    SERVED,     // Đã phục vụ
    COMPLETED,  // Hoàn thành
    CANCELLED   // Đã hủy
}
