package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.global.security.ClientAddressResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressResolverTests {
    private final ClientAddressResolver resolver =
            new ClientAddressResolver("127.0.0.1/32,::1/128,10.0.0.0/8");

    @Test
    void untrustedPeersCannotSpoofForwardedHeaders() {
        assertThat(resolve("203.0.113.10", "198.51.100.1")).isEqualTo("203.0.113.10");
        assertThat(resolve("127.0.0.1", "1.2.3.4, 198.51.100.1")).isEqualTo("198.51.100.1");
        assertThat(resolve("127.0.0.1", "198.51.100.1, 10.0.0.9")).isEqualTo("198.51.100.1");
    }

    @Test
    void invalidHeadersFallBackToTheSocketAndIpVariantsAreCanonicalized() {
        for (String value :
                new String[] {
                    "example.com",
                    "198.51.100.1,",
                    "999.1.1.1",
                    "[::1]:80",
                    "deadbeef",
                    "198.51.100.1\nother"
                }) assertThat(resolve("127.0.0.1", value)).isEqualTo("127.0.0.1");
        assertThat(resolve("::ffff:127.0.0.1", "::ffff:198.51.100.1")).isEqualTo("198.51.100.1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.1");
        assertThat(new ClientAddressResolver("").resolve(request)).isEqualTo("127.0.0.1");
    }

    private String resolve(String peer, String forwarded) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-For", forwarded);
        return resolver.resolve(request);
    }
}
