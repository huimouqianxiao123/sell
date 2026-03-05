package com.example.sell.tool;

import com.alibaba.fastjson.util.BiFunction;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @author 屈轩
 */
@Component
public class SearchTool implements BiFunction<String, String, List<String>> {
    @Override
    public List<String> apply(String s, String s2) {
        return List.of();
    }
}
