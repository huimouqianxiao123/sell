package com.example.sell.Inceptor;

import cn.hutool.json.JSONUtil;
import com.example.sell.common.UserContext;
import com.example.sell.common.UserInfo;
import com.example.sell.domain.vo.UserVO;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Slf4j
public class LoginInceptor implements HandlerInterceptor {
    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    private static final String LOGIN_TOKEN_KEY = "login:token:";
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader("Authorization");
        if (StringUtils.hasText(token) && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        // 也可以从参数获取（备用）
        if (!StringUtils.hasText(token)) {
            token = request.getParameter("token");
        }

        // 2. Token为空 = 未登录
        if (!StringUtils.hasText(token)) {
            sendError(response, 401, "未登录，请先登录");
            return false;
        }
       String tokenKey = LOGIN_TOKEN_KEY + token;
       Object obj = redisTemplate.opsForValue().get(tokenKey);
       if(obj==null){
           sendError(response, 401, "登录过期，请先登录");
           return false;
       }
       UserVO userVO;
       if (obj instanceof UserVO) {
           userVO = (UserVO) obj;
       } else if (obj instanceof LinkedHashMap<?, ?> map) {
           Object id = map.get("id");
           Object username = map.get("username");
           Object role = map.get("role");
           userVO = UserVO.builder()
                   .id(id == null ? null : Long.valueOf(id.toString()))
                   .username(username == null ? null : username.toString())
                   .role(role == null ? null : role.toString())
                   .build();
       } else {
           userVO = JSONUtil.toBean(JSONUtil.toJsonStr(obj), UserVO.class);
       }
       UserInfo userInfo = new UserInfo(userVO.getId(), userVO.getRole(), userVO.getUsername());
       UserContext.setUserInfoThreadLocal(userInfo);
       return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable ModelAndView modelAndView) throws Exception {
        HandlerInterceptor.super.postHandle(request, response, handler, modelAndView);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        UserContext.removeUserInfoThreadLocal();
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
    }

    private void sendError(HttpServletResponse response, int code, String message) throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(code);

        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("message", message);
        
        response.getWriter().write(cn.hutool.json.JSONUtil.toJsonStr(result));
    }
}
