package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProxyResolverTest {

    @Test
    void no_proxy_configured_yields_direct() {
        ProxyResolver resolver = ProxyResolver.fromEnv(Map.of());
        assertFalse(resolver.hasProxy());
        assertEquals(List.of(Proxy.NO_PROXY),
                resolver.select(URI.create("https://portal.example.com/api")));
    }

    @Test
    void https_proxy_honored_for_https_target() {
        ProxyResolver resolver = ProxyResolver.fromEnv(Map.of("HTTPS_PROXY", "http://proxy.corp:3128"));
        assertTrue(resolver.hasProxy());
        List<Proxy> proxies = resolver.select(URI.create("https://portal.example.com/api"));
        assertEquals(1, proxies.size());
        Proxy proxy = proxies.get(0);
        assertEquals(Proxy.Type.HTTP, proxy.type());
        InetSocketAddress address = (InetSocketAddress) proxy.address();
        assertEquals("proxy.corp", address.getHostString());
        assertEquals(3128, address.getPort());
    }

    @Test
    void https_proxy_applies_to_wss_scheme() {
        ProxyResolver resolver = ProxyResolver.fromEnv(Map.of("HTTPS_PROXY", "http://proxy.corp:3128"));
        List<Proxy> proxies = resolver.select(URI.create("wss://portal.example.com/api/runner/ws"));
        assertEquals(1, proxies.size());
        assertEquals(Proxy.Type.HTTP, proxies.get(0).type());
    }

    @Test
    void lowercase_env_variant_accepted() {
        ProxyResolver resolver = ProxyResolver.fromEnv(Map.of("https_proxy", "proxy.corp:8080"));
        InetSocketAddress address = (InetSocketAddress) resolver
                .select(URI.create("https://x.example.com")).get(0).address();
        assertEquals("proxy.corp", address.getHostString());
        assertEquals(8080, address.getPort());
    }

    @Test
    void no_proxy_excludes_matching_host() {
        ProxyResolver resolver = ProxyResolver.fromEnv(Map.of(
                "HTTPS_PROXY", "http://proxy.corp:3128",
                "NO_PROXY", "example.com,localhost"));
        assertEquals(List.of(Proxy.NO_PROXY),
                resolver.select(URI.create("https://portal.example.com/api")));
        assertEquals(List.of(Proxy.NO_PROXY),
                resolver.select(URI.create("https://example.com/api")));
    }

    @Test
    void no_proxy_does_not_exclude_other_hosts() {
        ProxyResolver resolver = ProxyResolver.fromEnv(Map.of(
                "HTTPS_PROXY", "http://proxy.corp:3128",
                "NO_PROXY", "internal.corp"));
        List<Proxy> proxies = resolver.select(URI.create("https://portal.example.com/api"));
        assertEquals(Proxy.Type.HTTP, proxies.get(0).type());
    }

    @Test
    void no_proxy_wildcard_excludes_everything() {
        ProxyResolver resolver = ProxyResolver.fromEnv(Map.of(
                "HTTPS_PROXY", "http://proxy.corp:3128",
                "NO_PROXY", "*"));
        assertEquals(List.of(Proxy.NO_PROXY),
                resolver.select(URI.create("https://anything.example.com")));
    }

    @Test
    void http_proxy_used_for_http_target() {
        ProxyResolver resolver = ProxyResolver.fromEnv(Map.of("HTTP_PROXY", "http://proxy.corp:3128"));
        List<Proxy> proxies = resolver.select(URI.create("http://portal.example.com/api"));
        assertEquals(Proxy.Type.HTTP, proxies.get(0).type());
    }
}
