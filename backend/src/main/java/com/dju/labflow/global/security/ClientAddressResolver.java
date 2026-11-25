package com.dju.labflow.global.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/** Only explicitly trusted socket peers can supply forwarding headers. Never resolve hostnames. */
@Component
public class ClientAddressResolver {
    private final List<IpAddressMatcher> trusted;

    public ClientAddressResolver(
            @Value("${labflow.http.trusted-proxies:127.0.0.1/32,::1/128}") String networks) {
        trusted =
                Arrays.stream(networks.split(","))
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .map(IpAddressMatcher::new)
                        .toList();
    }

    public String resolve(HttpServletRequest request) {
        String peer = literal(request.getRemoteAddr());
        if (peer == null) return "unknown";
        if (!trusted(peer)) return peer;
        String forwarded =
                String.join(",", Collections.list(request.getHeaders("X-Forwarded-For")));
        if (forwarded.isEmpty() || forwarded.length() > 1024) return peer;
        String[] chain = forwarded.split(",", -1);
        if (chain.length > 20) return peer;
        String[] addresses = new String[chain.length];
        for (int index = 0; index < chain.length; index++) {
            addresses[index] = literal(chain[index].trim());
            if (addresses[index] == null) return peer;
        }
        String current = peer;
        for (int index = addresses.length - 1; index >= 0 && trusted(current); index--)
            current = addresses[index];
        return current;
    }

    private boolean trusted(String address) {
        return trusted.stream().anyMatch(network -> network.matches(address));
    }

    private static String literal(String address) {
        if (address == null || address.length() > 45) return null;
        boolean ipv4 = address.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}");
        boolean ipv6 = address.contains(":") && address.matches("[0-9a-fA-F:.]+");
        if (!ipv4 && !ipv6) return null;
        try {
            if (ipv4) {
                String[] parts = address.split("\\.");
                byte[] octets = new byte[4];
                for (int index = 0; index < parts.length; index++) {
                    int octet = Integer.parseInt(parts[index]);
                    if (octet > 255) return null;
                    octets[index] = (byte) octet;
                }
                return InetAddress.getByAddress(octets).getHostAddress();
            }
            // A colon forces literal IPv6 parsing; invalid values never enter hostname lookup.
            return InetAddress.getByName(address).getHostAddress();
        } catch (UnknownHostException invalid) {
            return null;
        }
    }
}
