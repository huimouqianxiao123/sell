package com.example.sell.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.sell.domain.Dto.OrderListRequest;
import com.example.sell.domain.pojo.Orders;
import com.example.sell.domain.vo.OrderDetailVO;
import com.example.sell.domain.vo.OrderVO;

import java.util.List;

/**
 * @author 屈轩
 */
public interface OrderService extends IService<Orders> {
    String createOrder(OrderListRequest orderListRequest);

    List<OrderVO> getOrderList();

    OrderDetailVO getOrderDetail(Long orderId);

    /**
     * 根据订单号查询订单详情
     * @param orderNo 订单号
     * @return 订单详情
     */
    OrderDetailVO getOrderDetailByOrderNo(String orderNo);

    void cancel(Long orderId);

    void confirmReceive(Long orderId);
}
