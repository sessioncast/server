package com.tmuxremote.relay.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class DomainFilter {

    @Value("${app.auth.allowed-domains:}")
    private String allowedDomainsConfig;

    public boolean isAllowedDomain(String email) {
        if (allowedDomainsConfig == null || allowedDomainsConfig.isBlank()) {
            log.warn("No allowed domains configured - allowing all domains");
            return true;
        }

        List<String> allowedDomains = Arrays.asList(allowedDomainsConfig.split(","));

        String domain = extractDomain(email);
        boolean allowed = allowedDomains.stream()
                .map(String::trim)
                .anyMatch(d -> d.equalsIgnoreCase(domain));

        if (!allowed) {
            log.warn("Domain not allowed: {} (email: {})", domain, email);
        }

        return allowed;
    }

    private String extractDomain(String email) {
        if (email == null || !email.contains("@")) {
            return "";
        }
        return email.substring(email.indexOf("@") + 1);
    }
}
