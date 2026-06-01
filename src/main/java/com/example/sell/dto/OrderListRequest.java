package com.example.sell.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author 屈轩
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderListRequest {
    private List<OrderRequest> orderRequests;


}
