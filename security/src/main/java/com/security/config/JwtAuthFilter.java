package com.security.config;

import com.security.services.JwtService;
import com.security.services.BlacklistService;
import com.logging.services.LoggingService;
import com.logging.models.LogContext;
import com.handle_exceptions.UnauthorizedExceptionHandle;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    @Autowired
    JwtService jwtService;
    @Autowired
    UserDetailsService userDetailsService;
    @Autowired
    LoggingService loggingService;
    @Autowired
    BlacklistService blacklistService;

    @Autowired
    @Qualifier("handlerExceptionResolver")
    private HandlerExceptionResolver handlerExceptionResolver;

    private LogContext getLogContext(String methodName) {
        return LogContext.builder()
            .module("security")
            .className(this.getClass().getSimpleName())
            .methodName(methodName)
            .build();
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        
        LogContext logContext = getLogContext("doFilterInternal");

        loggingService.logDebug("Processing request: " + request.getRequestURI(), logContext);
        String authHeader = request.getHeader("Authorization");
        String jwt;
        String username;
                
        // nếu là public endpoint thì skip jwt validation,
        // vì JwtAuthFilter chạy trước nên xử lý thêm ở đây sẽ hiệu quả hơn
        if (isPublicEndpoint(request.getRequestURI())) {
             loggingService.logDebug("Skipping JWT validation for public endpoint: "+ request.getRequestURI()
                     , logContext);
             filterChain.doFilter(request, response);
             return;
        }

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            loggingService.logDebug("No Authorization header or not Bearer token for: " + request.getRequestURI()
            , logContext);
            UnauthorizedExceptionHandle exception = new UnauthorizedExceptionHandle(
                "Missing or invalid Authorization header",
                "Authorization header must start with 'Bearer '"
            );
            handlerExceptionResolver.resolveException(request, response, null, exception);
            return;
        }

        try {
            jwt = authHeader.substring(7); // bỏ "Bearer " để lấy token từ header
            username = jwtService.extractUsername(jwt); // lấy username từ token
            loggingService.logDebug("Extracted username from JWT: " + username, logContext);

            // Kiểm tra user token có bị blacklist không (dựa trên username)
            if (blacklistService.isUserTokenBlacklisted(jwt, username)) {
                loggingService.logWarn("User token is blacklisted for user: " + username, logContext);
                UnauthorizedExceptionHandle exception = new UnauthorizedExceptionHandle(
                    "User token is blacklisted",
                    "User has been logged out from all devices"
                );
                handlerExceptionResolver.resolveException(request, response, null, exception);
                return;
            }

            // kiểm tra username có tồn tại không, và đã authentication chưa để tránh lặp lại
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null){
                UserDetails userDetails = this.userDetailsService.loadUserByUsername(username); // lấy user details từ database
                if(jwtService.isTokenValid(jwt, userDetails)) { // kiểm tra username có tồn tại không, và token còn hạn sử dụng không
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities()
                    );

                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    // set authentication vào security context
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    loggingService.logDebug("Successfully authenticated user: " + username, logContext);
                    /* 
                    lúc này có thể gửi thêm data (không nhạy cảm) sang endpoint ở service khác
                    nhưng chỉ nên gửi các bất đồng bộ như kafka message tránh làm chậm response
                    */
                } else {
                    loggingService.logWarn("Invalid JWT token for user:" + username, logContext);
                    UnauthorizedExceptionHandle exception = new UnauthorizedExceptionHandle(
                        "Token validation failed",
                        "Token may be invalid, expired, or user not found"
                    );
                    handlerExceptionResolver.resolveException(request, response, null, exception);
                    return;
                }
            }
        } catch (Exception e) {
            loggingService.logError("Error processing JWT token: " + e.getMessage(), e, logContext);
            // Clear security context nếu có lỗi
            SecurityContextHolder.clearContext();
            
            UnauthorizedExceptionHandle exception = new UnauthorizedExceptionHandle(
                "Token validation failed",
                "Token may be invalid, expired, or malformed"
            );
            handlerExceptionResolver.resolveException(request, response, null, exception);
            return;
        }
        filterChain.doFilter(request, response);
    }

        // nếu là public endpoint thì skip jwt validation,
        // vì JwtAuthFilter chạy trước nên xử lý thêm ở đây sẽ hiệu quả hơn

     private boolean isPublicEndpoint(String uri) {
         return uri.equals("/auth/register") ||
                uri.equals("/auth/login") ||
                uri.startsWith("/auth/internal/");  // Internal APIs cho module-to-module
     }
}
