package com.nageoffer.shortlink.aigateway.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiGatewayErrorRespDTO {

    private Integer status;

    private String message;
}
