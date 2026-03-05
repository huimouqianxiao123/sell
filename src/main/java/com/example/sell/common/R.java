package com.example.sell.common;

import lombok.Data;
import lombok.experimental.Accessors;

import static com.example.sell.common.enums.ErrorInfo.Code.FAILED;
import static com.example.sell.common.enums.ErrorInfo.Code.SUCCESS;
import static com.example.sell.common.enums.ErrorInfo.Msg.OK;


/**
 * 通用响应结果
 * @author 杨正
 * */
@Data
@Accessors(chain = true)
public class R<T> {
    /**
     * 业务状态码，200-成功，其它-失败
     */
    private int code;
    /**
     * 响应消息
     * */
    private String msg;
    /**
     * 响应数据
     * */
    private T data;
    /**
     * 请求id
     * */
//    private String requestId;

    public static R<Void> ok() {
        return new R<>(SUCCESS, OK, null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(SUCCESS, OK, data);
    }

    public static <T> R<T> error(String msg) {
        return new R<>(FAILED, msg, null);
    }

    public static <T> R<T> error(int code, String msg) {
        return new R<>(code, msg, null);
    }

    public R() {
    }

    public R(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public boolean success(){
        return code == SUCCESS;
    }
}
