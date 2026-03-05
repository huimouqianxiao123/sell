package com.example.sell.common;


/**
 * @author 屈轩
 */

public class UserContext {
    private static ThreadLocal<UserInfo> userInfoThreadLocal=new ThreadLocal<>();

    public static void setUserInfoThreadLocal(UserInfo userInfo){
        userInfoThreadLocal.set(userInfo);
    }
    public static void removeUserInfoThreadLocal(){
        userInfoThreadLocal.remove();
    }
    public static UserInfo getUserInfo(){
        return userInfoThreadLocal.get();
    }
}
