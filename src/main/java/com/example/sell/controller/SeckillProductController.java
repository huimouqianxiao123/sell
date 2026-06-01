package com.example.sell.controller;

import com.example.sell.common.R;
import com.example.sell.dto.SeckillProductRequest;
import com.example.sell.vo.SeckillProductDetailVo;
import com.example.sell.service.SeckillProductService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * @author 屈轩
 */
@RestController
@RequestMapping("/api")
public class SeckillProductController {
    @Resource
    private SeckillProductService seckillProductService;

    @PostMapping("/addSeckillProduct")
    public R<String> addSeckillProduct(@RequestBody SeckillProductRequest seckillProductRequest) {
        seckillProductService.addSeckillProduct(seckillProductRequest);
        return R.ok("Success");
    }

    /**
     * 点击秒杀
     * 
     * @param id
     * @return
     */
    @PostMapping("/startSeckill")
    public R<String> startSeckill(@RequestParam Long id) {
        String result = seckillProductService.startSeckill(id);
        return R.ok(result);
    }

    /**
     * 获得秒杀订单号
     * 
     * @return
     */
    @GetMapping("/result")
    public R<String> getOrderNo() {
        String orderNo = seckillProductService.getOrderNo();
        return R.ok(orderNo);
    }

    @GetMapping("/miaoshalist")
    public R<List<SeckillProductDetailVo>> getMiaoshaList() {
        List<SeckillProductDetailVo> list = seckillProductService.getSeckillProductList();
        return R.ok(list);
    }

    /**
     * 查询秒杀订单数量（用于压测时的消费统计）
     * 
     * @param productId 秒杀商品ID
     * @return 订单数量统计
     */
    @GetMapping("/seckillOrderCount")
    public R<Map<String, Object>> getSeckillOrderCount(@RequestParam Long productId) {
        Map<String, Object> result = seckillProductService.getSeckillOrderCount(productId);
        return R.ok(result);
    }
}
