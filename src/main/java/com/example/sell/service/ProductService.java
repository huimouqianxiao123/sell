package com.example.sell.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.sell.domain.Dto.ProductRequest;
import com.example.sell.domain.pojo.Product;

import java.util.List;

public interface ProductService extends IService<Product> {
    List<Product> listProducts();

    Page<Product> pageProducts(Integer page, Integer size, String name);


    void saveOrUpdateProduct(ProductRequest productRequest);

    void deleteProductById(Long id);

    Product getProductById(Long id);

    void updateProduct(ProductRequest productRequest);


}
