package com.guoyongzheng.training.web.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyFilterTest {

    @Test
    void blankApiKeyDisablesApiAuthenticationForLocalDevelopment() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter("  ");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/training/stats");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void configuredApiKeyAllowsMatchingHeader() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter("secret-key");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/training/stats");
        request.addHeader("X-API-Key", "secret-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void configuredApiKeyRejectsMissingHeaderForApiPaths() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter("secret-key");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/training/stats");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Missing or invalid X-API-Key header");
    }

    @Test
    void configuredApiKeyAllowsCorsPreflightWithoutHeader() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter("secret-key");
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/training/stats");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void configuredApiKeyKeepsHealthEndpointOpen() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter("secret-key");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
