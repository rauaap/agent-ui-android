package com.agentui.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class SandboxNetworkDestinationTest {
    @Test public void responseRoundTripPreservesExactPairs() throws Exception {
        List<SandboxNetworkDestination> entries = SandboxNetworkDestination.from(new JSONArray(
                "[{\"ip\":\"100.64.0.10\",\"port\":443},{\"ip\":\"100.64.0.10\",\"port\":22}]"));
        assertEquals(2, entries.size());
        assertEquals("100.64.0.10", entries.get(0).ip);
        assertEquals(443, entries.get(0).port);
        JSONObject payload = SandboxNetworkDestination.replacement(entries);
        assertEquals(1, payload.length());
        assertTrue(payload.getJSONArray("sandbox_network_allowlist").getJSONObject(1).get("port") instanceof Integer);
        assertEquals(22, payload.getJSONArray("sandbox_network_allowlist").getJSONObject(1).getInt("port"));
    }

    @Test public void clearingSendsRequiredEmptyArray() throws Exception {
        JSONObject payload = SandboxNetworkDestination.replacement(new ArrayList<>());
        assertEquals(1, payload.length());
        assertEquals("[]", payload.getJSONArray("sandbox_network_allowlist").toString());
    }

    @Test public void normalizedResponseReplacesSubmittedDuplicates() throws Exception {
        List<SandboxNetworkDestination> draft = Arrays.asList(
                new SandboxNetworkDestination("100.64.0.10", 443),
                new SandboxNetworkDestination("100.64.0.10", 443));
        assertEquals(2, SandboxNetworkDestination.toJson(draft).length());
        List<SandboxNetworkDestination> saved = SandboxNetworkDestination.from(new JSONArray(
                "[{\"ip\":\"100.64.0.10\",\"port\":443}]"));
        assertEquals(1, saved.size());
    }

    @Test public void ipv4SyntaxNeverResolvesHostnames() {
        assertTrue(SandboxNetworkDestination.isIpv4Literal("100.64.0.10"));
        assertTrue(SandboxNetworkDestination.isIpv4Literal("192.168.1.1"));
        for (String ip : Arrays.asList("host.local", "::1", "100.64.0.10/32", "1.2.3", "1.2.3.256",
                "1.2.3.-1", "1.2.3.4.", "01.2.3.4", " 1.2.3.4", "1..3.4")) {
            assertFalse(ip, SandboxNetworkDestination.isIpv4Literal(ip));
        }
        // Destination policy (unicast/reserved ranges) remains server-authoritative.
    }

    @Test public void portsAreIntegersInTcpRange() {
        assertEquals(1, SandboxNetworkDestination.parsePort("1"));
        assertEquals(65535, SandboxNetworkDestination.parsePort("65535"));
        for (String port : Arrays.asList("", "0", "65536", "-1", "1.5", "1e2", "443/tcp", "9999999999")) {
            try {
                SandboxNetworkDestination.parsePort(port);
                fail("Accepted invalid port " + port);
            } catch (IllegalArgumentException expected) { }
        }
    }
}
