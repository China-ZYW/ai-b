package com.aibi.module.dashboard.dto;

/**
 * 大屏分享配置表单（问题2）。
 *
 * @param enabled     是否开启公开分享；{@code false} 关闭并清除令牌/密码/有效期，旧链接立即失效
 * @param password    访问密码（明文，服务端 BCrypt 落库）；留空=不设密码
 * @param expireHours 有效期小时数；{@code null}=不过期
 */
public record DashboardShareForm(
        Boolean enabled,
        String password,
        Integer expireHours) {
}
