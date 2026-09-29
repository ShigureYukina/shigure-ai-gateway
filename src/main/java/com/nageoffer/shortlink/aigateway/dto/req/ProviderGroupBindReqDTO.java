package com.nageoffer.shortlink.aigateway.dto.req;

import lombok.Data;

import java.util.List;

/**
 * 模型绑定到通道组的请求。
 */
@Data
public class ProviderGroupBindReqDTO {

    private List<String> models;
}
