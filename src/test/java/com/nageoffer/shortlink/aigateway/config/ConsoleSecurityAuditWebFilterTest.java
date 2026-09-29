package com.nageoffer.shortlink.aigateway.config;

import com.nageoffer.shortlink.aigateway.audit.AuditLogService;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.security.ConsoleAuthService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * 管理面鉴权过滤器的行为锁：数据面必须完全绕过控制台令牌，管理面必须每次都验。
 */
class ConsoleSecurityAuditWebFilterTest {

    private AiGatewayProperties properties;
    private ConsoleAuthService consoleAuthService;
    private AuditLogService auditLogService;
    private WebFilterChain chain;
    private WebFilter filter;

    @BeforeEach
    void setUp() {
        properties = new AiGatewayProperties();
        consoleAuthService = Mockito.mock(ConsoleAuthService.class);
        auditLogService = Mockito.mock(AuditLogService.class);
        chain = Mockito.mock(WebFilterChain.class);
        filter = new ConsoleSecurityAuditWebFilter(properties, consoleAuthService, auditLogService).consoleSecurityWebFilter();
        Mockito.when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private void invoke(HttpMethod method, String path) {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, path).build());
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
    }

    private void stubPrincipal(String username, String role) {
        Mockito.when(consoleAuthService.authenticate(any()))
                .thenReturn(new ConsoleAuthService.AuthPrincipal(username, role));
    }

    @Test
    void shouldBypassConsoleAuthForEveryDataPlaneEndpoint() {
        properties.getSecurity().setEnabled(true);

        invoke(HttpMethod.POST, "/v1/chat/completions");
        invoke(HttpMethod.GET, "/v1/models");
        invoke(HttpMethod.POST, "/v1/messages");
        invoke(HttpMethod.POST, "/v1/messages/count_tokens");
        invoke(HttpMethod.POST, "/v1/responses");

        Mockito.verify(consoleAuthService, Mockito.never()).authenticate(any());
        Mockito.verify(chain, Mockito.times(5)).filter(any());
    }

    @Test
    void shouldAlwaysAllowLoginEvenWhenConsoleAuthIsEnabled() {
        properties.getSecurity().setEnabled(true);

        invoke(HttpMethod.POST, "/v1/security/login");

        Mockito.verify(consoleAuthService, Mockito.never()).authenticate(any());
        Mockito.verify(chain).filter(any());
    }

    @Test
    void shouldPassThroughNonV1Paths() {
        properties.getSecurity().setEnabled(true);

        invoke(HttpMethod.GET, "/actuator/health");

        Mockito.verify(consoleAuthService, Mockito.never()).authenticate(any());
    }

    @Test
    void shouldSkipAuthenticationWhenConsoleAuthDisabled() {
        properties.getSecurity().setEnabled(false);

        invoke(HttpMethod.POST, "/v1/rate-limit/config");

        Mockito.verify(consoleAuthService, Mockito.never()).authenticate(any());
        Mockito.verify(consoleAuthService, Mockito.never()).assertWriteAllowed(any());
        Mockito.verify(chain).filter(any());
    }

    @Test
    void shouldRejectManagementRequestWithoutToken() {
        properties.getSecurity().setEnabled(true);
        Mockito.when(consoleAuthService.authenticate(any()))
                .thenThrow(new AiGatewayClientException(AiGatewayErrorCode.UNAUTHORIZED, "缺少 X-Console-Token"));

        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/rate-limit/config").build());

        Assertions.assertThrows(AiGatewayClientException.class, () -> filter.filter(exchange, chain));
        Mockito.verify(chain, Mockito.never()).filter(any());
    }

    @Test
    void shouldRequireWriteRoleForWriteMethodsAndRecordAudit() {
        properties.getSecurity().setEnabled(true);
        stubPrincipal("alice", "admin");

        invoke(HttpMethod.POST, "/v1/rate-limit/config");

        Mockito.verify(consoleAuthService).assertWriteAllowed(eq(new ConsoleAuthService.AuthPrincipal("alice", "admin")));
        Mockito.verify(auditLogService).record(eq("alice"), eq("admin"), eq("HTTP_POST"), eq("/v1/rate-limit/config"), eq(true), eq("status=200"));
    }

    @Test
    void shouldNotRequireWriteRoleForReadMethods() {
        properties.getSecurity().setEnabled(true);
        stubPrincipal("viewer", "viewer");

        invoke(HttpMethod.GET, "/v1/routing/config");

        Mockito.verify(consoleAuthService, Mockito.never()).assertWriteAllowed(any());
        Mockito.verify(chain).filter(any());
    }

    @Test
    void shouldNotRequireWriteRoleForLogoutBecauseLogoutMustAlwaysSucceed() {
        properties.getSecurity().setEnabled(true);
        stubPrincipal("viewer", "viewer");

        invoke(HttpMethod.POST, "/v1/security/logout");

        Mockito.verify(consoleAuthService, Mockito.never()).assertWriteAllowed(any());
        Mockito.verify(chain).filter(any());
    }

    @Test
    void shouldRecordFailedRequestsIntoAuditLog() {
        properties.getSecurity().setEnabled(true);
        stubPrincipal("alice", "admin");
        AiGatewayClientException failure = new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST, "参数不合法");
        Mockito.when(chain.filter(any())).thenReturn(Mono.error(failure));

        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/routing/config").build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyError(AiGatewayClientException.class);
        Mockito.verify(auditLogService).record(eq("alice"), eq("admin"), eq("HTTP_POST"), eq("/v1/routing/config"), eq(false), eq("参数不合法"));
    }

    @Test
    void shouldExposeDataPlanePathsIndependentlyOfConfiguredProviders() {
        // 数据面绕过是路径维度的事实判断，不能因为 routing 配了哪些渠道而改变
        properties.getSecurity().setEnabled(true);
        properties.getRouting().setProviderPriority(List.of("openai", "claude"));

        invoke(HttpMethod.POST, "/v1/messages");

        Mockito.verify(consoleAuthService, Mockito.never()).authenticate(any());
    }
}
