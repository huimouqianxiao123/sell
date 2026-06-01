package com.example.sell.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.sell.common.R;
import com.example.sell.dto.ProductRequest;
import com.example.sell.entity.Product;
import com.example.sell.service.ProductService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/product")
public class ProductController {

    @Resource
    private ProductService productService;

    @GetMapping("/list")
    public R<List<Product>> list() {
        return R.ok(productService.listProducts());
    }

    @GetMapping("/page")
    public R<Page<Product>> page(@RequestParam(defaultValue = "1") Integer page,
                                 @RequestParam(defaultValue = "10") Integer size,
                                 @RequestParam(required = false) String name) {
        return R.ok(productService.pageProducts(page, size, name));
    }

    @PostMapping("/save")
    public R<String> save(@RequestBody ProductRequest productRequest) {
        productService.saveOrUpdateProduct(productRequest);
        return R.ok("Success");
    }

    /**
     * 增加库存
     * @param productRequest
     * @return
     */
    @PostMapping("/update")
    public R<String> update(@RequestBody ProductRequest productRequest) {
        productService.updateProduct(productRequest);
        return R.ok("商品修改成功");
    }

    @DeleteMapping("/{id}")
    public R<String> delete(@PathVariable Long id) {
        productService.deleteProductById(id);
        return R.ok("Deleted");
    }
    
    @GetMapping("/{id}")
    public R<Product> getById(@PathVariable Long id) {
        return R.ok(productService.getProductById(id));
    }


}
