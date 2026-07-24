package com.npuhub.web;

import com.npuhub.service.InferenceApiStateService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class InferenceApiGateFilter extends OncePerRequestFilter {
    private final InferenceApiStateService apiStateService;

    public InferenceApiGateFilter(InferenceApiStateService apiStateService) {
        this.apiStateService = apiStateService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!apiStateService.isEnabled()
                && "POST".equalsIgnoreCase(request.getMethod())
                && isInferencePath(request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"Inference API is stopped. Load a model and start the API from the control panel.\"}"
            );
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isInferencePath(String path) {
        return "/api/chat".equals(path)
                || "/api/generate".equals(path)
                || "/v1/chat/completions".equals(path)
                || "/v1/completions".equals(path);
    }
}
