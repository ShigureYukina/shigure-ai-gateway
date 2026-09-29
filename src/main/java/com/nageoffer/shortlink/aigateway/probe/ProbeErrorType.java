package com.nageoffer.shortlink.aigateway.probe;

/**
 * 探测失败分类。
 * <p>
 * 分类的意义在于<b>运维动作不同</b>，不是为了日志好看：
 * <ul>
 *   <li>{@link #NO_CREDENTIAL}：没配 Key。动作是去补凭证，重试没用。</li>
 *   <li>{@link #UNAUTHORIZED} / {@link #FORBIDDEN}：Key 失效或无权限。动作是换 Key / 提权。</li>
 *   <li>{@link #RATE_LIMITED}：被限流。<b>不该判渠道不可用</b> —— 限流是流量问题，
 *       上游本身是好的，把它禁用只会让可用容量更小。</li>
 *   <li>{@link #UPSTREAM_5XX}：上游自己坏了。动作是等它恢复或切走。</li>
 *   <li>{@link #TIMEOUT} / {@link #CONNECT_FAILED}：网络层不通。动作是查网络与地址配置。</li>
 *   <li>{@link #BAD_RESPONSE}：通了但拿到的不是预期内容（含 200 却解析不出任何模型、
 *       其他 4xx、以及响应体超限）。动作是查上游协议或路径配置。</li>
 * </ul>
 * 因此 {@code ChannelProbeService} 只把"渠道本身不行"的几类计入禁用计数，
 * {@link #RATE_LIMITED} 与 {@link #NO_CREDENTIAL} 不计入 —— 见该类的注释。
 */
public enum ProbeErrorType {

    /**
     * 探测成功。
     */
    OK,

    /**
     * 渠道没配凭证。
     */
    NO_CREDENTIAL,

    /**
     * 401：Key 失效。
     */
    UNAUTHORIZED,

    /**
     * 403：Key 有效但无权限（例如模型未开通）。
     */
    FORBIDDEN,

    /**
     * 429：被限流。
     */
    RATE_LIMITED,

    /**
     * 5xx：上游自身故障。
     */
    UPSTREAM_5XX,

    /**
     * 超时。
     */
    TIMEOUT,

    /**
     * 连不上：连接被拒 / 域名解析不了 / TLS 握手失败。
     */
    CONNECT_FAILED,

    /**
     * 通了但内容不对：200 却解析不出模型、其他 4xx、响应体超过上限。
     */
    BAD_RESPONSE
}
