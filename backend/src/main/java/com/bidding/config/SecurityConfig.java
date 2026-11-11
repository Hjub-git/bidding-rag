package com.bidding.config;

import com.bidding.common.Result;
import com.bidding.common.ResultCode;
import com.bidding.security.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * Spring Security 核心配置。
 * 思路：CSRF 关闭（无状态 JWT）+ 无会话 + 放行登录注册与公开读 + 其余需认证；
 * 具体“哪些角色能调哪个接口”用 @PreAuthorize 写在 Controller 方法上（见 @EnableMethodSecurity）。
 * 设计要点：SecurityFilterChain、过滤器链顺序、认证入口/拒绝处理器如何返回统一格式。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // ① SSE 的 ASYNC dispatch 必须显式放行。
                //    JwtAuthenticationFilter 是 OncePerRequestFilter，默认跳过 async dispatch，
                //    于是 async 续传时 SecurityContext 为空；若不在这里放行，AuthorizationFilter
                //    会把同一请求判定为"未认证"→ 403，且此时响应已提交，异常处理器也救不回来
                //    （日志：Unable to handle the Spring Security Exception because the response is already committed）。
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                // ② 文件相关接口必须显式挡在通配 permitAll 之前。
                //    matcher 顺序即优先级，第一条命中即生效：若挂在下面的 GET /api/tenders/** 之后，
                //    未登录就能枚举所有项目的文件名、页数、chunk 数、状态。
                .requestMatchers(HttpMethod.GET,
                        "/api/tenders/*/documents", "/api/tenders/*/documents/*").authenticated()
                // ② 登录、注册公开
                .requestMatchers("/api/auth/**").permitAll()
                // ③ 招标列表 / 留言板 公开读（详情走同一前缀）——公开面保持不变
                .requestMatchers(HttpMethod.GET, "/api/tenders/**", "/api/messages/**").permitAll()
                // ④ RAG 全部接口要求登录；/api/rag/health 含内部拓扑，不能当公开探针
                .requestMatchers("/api/rag/**").authenticated()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> writeJson(res, ResultCode.UNAUTHORIZED))
                .accessDeniedHandler((req, res, ex) -> writeJson(res, ResultCode.FORBIDDEN)))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void writeJson(HttpServletResponse response, ResultCode code) throws IOException {
        response.setStatus(code.getCode());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.error(code.getCode(), code.getMessage())));
    }
}
