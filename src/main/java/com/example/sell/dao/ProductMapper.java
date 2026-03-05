package com.example.sell.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.sell.domain.pojo.Product;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author 屈轩
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

}
