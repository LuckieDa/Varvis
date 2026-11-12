package org.nbscn.varvis.variantanalysis.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;

/** Isolated saved-observation operations, with no new science budget. */
@Configuration
@ConditionalOnExpression("${variant-analysis.acmg-business.saved-observation-association-only:false} || ${variant-analysis.acmg-business.saved-observation-calibration-only:false}")
public class SavedObservationOnlyConfig {
    public static boolean allowed(String method, String path) {
        // The shadow-preview GET may perform an external model call; it is not a saved read.
        return Set.of("GET", "HEAD", "OPTIONS").contains(method) && !path.endsWith("/llm-shadow-preview")
                || "POST".equals(method) && path.matches("/variant-analyses/[a-f0-9-]{36}/acmg/runs");
    }
    public static boolean calibrationAllowed(String method, String path) {
        if ("POST".equals(method)) return path.matches("/variant-analyses/[a-f0-9-]{36}/acmg/runs");
        if (!Set.of("GET", "HEAD", "OPTIONS").contains(method)) return false;
        if (path.equals("/") || path.matches("/[^?]*\\.(html|js|css|json|png|jpg|svg|ico|woff2?|ttf)$")) return true;
        // phenotype-prior can read the KB even on GET; calibration only reads saved evidence.
        return path.matches("/variant-analyses/[a-f0-9-]{36}(/(variants|events|diagnostics|exports/(json|csv)|acmg/input-options|acmg/runs(/[a-f0-9-]{36})?|candidates/[^/]+(/acmg|/ranking-explanation)?))?");
    }
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> savedObservationOnlyFilter(Environment env) {
        if (!"PP3_BP4_DEVELOPMENT".equals(env.getProperty("variant-analysis.acmg-business.acceptance-scope")))
            throw new IllegalArgumentException("SAVED_OBSERVATION_DEVELOPMENT_SCOPE_REQUIRED");
        boolean calibration = env.getProperty("variant-analysis.acmg-business.saved-observation-calibration-only", Boolean.class, false);
        var filter = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(@org.springframework.lang.NonNull HttpServletRequest request,
                    @org.springframework.lang.NonNull HttpServletResponse response, @org.springframework.lang.NonNull FilterChain chain)
                    throws ServletException, IOException {
                if (calibration ? calibrationAllowed(request.getMethod(), request.getServletPath())
                        : allowed(request.getMethod(), request.getServletPath())) { chain.doFilter(request, response); return; }
                response.setStatus(403); response.setContentType("application/json");
                response.getWriter().write(calibration ? "{\"message\":\"NEW_SCIENTIFIC_ACQUISITION_DISABLED_CALIBRATION_ONLY\"}"
                        : "{\"message\":\"NEW_SCIENTIFIC_ACQUISITION_DISABLED_ASSOCIATION_ONLY\"}");
            }
        };
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        registration.setOrder(-100); registration.addUrlPatterns("/*"); return registration;
    }
}
