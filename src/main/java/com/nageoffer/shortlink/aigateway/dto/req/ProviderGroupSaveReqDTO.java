package com.nageoffer.shortlink.aigateway.dto.req;

import lombok.Data;

import java.util.List;

/**
 * 通道组保存请求。
 */
@Data
public class ProviderGroupSaveReqDTO {

    private String groupName;

    /**
     * PRIORITY / ROUND_ROBIN / RANDOM / WEIGHTED / DYNAMIC，缺省 PRIORITY。
     */
    private String strategy;

    private Boolean enabled;

    private String description;

    private List<Member> members;

    @Data
    public static class Member {

        private String provider;

        /**
         * 可选：该 provider 上的真实模型名。
         */
        private String model;

        private Integer weight;

        private Integer priority;
    }
}
