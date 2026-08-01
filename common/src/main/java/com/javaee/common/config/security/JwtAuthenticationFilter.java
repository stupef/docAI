package com.javaee.common.config.security;

import com.javaee.common.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * JWT认证过滤器
 * 用于验证请求中的JWT令牌并设置认证上下文
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, jakarta.servlet.FilterChain chain) 
            throws ServletException, IOException {
        try {
            // 从请求头中获取令牌
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                // 提取令牌
                String token = authHeader.substring("Bearer ".length());
                
                // 验证令牌
                if (JwtUtils.validateToken(token)) {
                    // 解析令牌获取用户信息
                    Claims claims = JwtUtils.parseToken(token);
                    Long userId = claims.get("userId", Long.class);
                    String role = claims.get("role", String.class);
                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                    addGroupAuthorities(authorities, claims.get("permissionGroups"));
                    addGroupAuthorities(authorities, claims.get("groups"));
                    
                    // 创建认证令牌
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            userId, null, authorities
                    );
                    
                    // 设置认证详情
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    
                    // 设置安全上下文
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (Exception e) {
            // 令牌验证失败，清除认证上下文
            SecurityContextHolder.clearContext();
        }

        // 兜底：如果本地没能从JWT建立身份，则信任网关注入的 X-User-Id / X-Role 头
        // （网关 AuthGlobalFilter 已完成JWT校验后才会注入这些头，并会剥离客户端伪造的同名头）
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            trustGatewayHeaders(request);
        }

        // 继续过滤链
        chain.doFilter(request, response);
    }

    /**
     * 信任网关注入的身份头（API网关标准做法：网关认证一次，下游直接复用结果）
     */
    private void trustGatewayHeaders(jakarta.servlet.http.HttpServletRequest request) {
        String userIdHeader = request.getHeader("X-User-Id");
        String role = request.getHeader("X-Role");
        if (userIdHeader == null || userIdHeader.isBlank() || role == null || role.isBlank()) {
            return;
        }
        try {
            Long userId = Long.valueOf(userIdHeader.trim());
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.trim()));

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    userId, null, authorities
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (NumberFormatException ignored) {
            // X-User-Id 不是合法数字，放弃兜底（保持匿名）
        }
    }

    private void addGroupAuthorities(List<SimpleGrantedAuthority> authorities, Object groupsClaim) {
        if (groupsClaim instanceof Collection<?> groups) {
            for (Object group : groups) {
                addGroupAuthority(authorities, group);
            }
            return;
        }
        if (groupsClaim instanceof String groups) {
            for (String group : groups.split("[,;]")) {
                addGroupAuthority(authorities, group);
            }
        }
    }

    private void addGroupAuthority(List<SimpleGrantedAuthority> authorities, Object rawGroup) {
        if (rawGroup == null) {
            return;
        }
        String group = rawGroup.toString().trim();
        if (group.isEmpty()) {
            return;
        }
        String authority = group.startsWith("GROUP_") ? group : "GROUP_" + group;
        authorities.add(new SimpleGrantedAuthority(authority));
    }
}
