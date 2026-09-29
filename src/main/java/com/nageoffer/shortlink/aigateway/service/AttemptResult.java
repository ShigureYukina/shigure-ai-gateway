package com.nageoffer.shortlink.aigateway.service;

/**
 * 一次成功的非流式上游调用结果。
 * <p>
 * 带上实际服务的 provider/model 而不是复用请求时的路由结果：回退发生后两者会不同，
 * 用量结算与指标上报必须按实际服务方记账。
 */
public record AttemptResult(String provider, String providerModel, String body) {
}
