package it.szn.prenotazionesale.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ClientIpResolver - Risoluzione sicura Client IP anti-spoofing")
class ClientIpResolverTest {

    private ClientIpResolver resolver;

    @BeforeEach
    void setUp() {
        AppConfig appConfig = new AppConfig();
        // Usa le configurazioni di default: 127.0.0.0/8, ::1/128, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
        resolver = new ClientIpResolver(appConfig);
    }

    @Test
    @DisplayName("a) request == null restituisce UNKNOWN")
    void testResolve_NullRequest_ReturnsUnknown() {
        assertEquals("UNKNOWN", resolver.resolve(null));
    }

    @Test
    @DisplayName("b) remoteAddr null o blank restituisce UNKNOWN")
    void testResolve_NullOrBlankRemoteAddr_ReturnsUnknown() {
        MockHttpServletRequest requestNull = new MockHttpServletRequest();
        requestNull.setRemoteAddr(null);
        assertEquals("UNKNOWN", resolver.resolve(requestNull));

        MockHttpServletRequest requestBlank = new MockHttpServletRequest();
        requestBlank.setRemoteAddr("   ");
        assertEquals("UNKNOWN", resolver.resolve(requestBlank));
    }

    @Test
    @DisplayName("c) remoteAddr non fidato ignora completamente XFF e restituisce remoteAddr")
    void testResolve_UntrustedRemoteAddr_IgnoresXff() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.5");
        request.addHeader("X-Forwarded-For", "198.51.100.1");

        assertEquals("203.0.113.5", resolver.resolve(request));
    }

    @Test
    @DisplayName("d) remoteAddr fidato con singolo IP non fidato in XFF restituisce l'IP del client")
    void testResolve_TrustedRemoteAddr_SingleClientIp_ReturnsClientIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.5");

        assertEquals("203.0.113.5", resolver.resolve(request));
    }

    @Test
    @DisplayName("e) remoteAddr fidato con spoofing in XFF scarta hop fidati e restituisce primo IP non fidato da destra")
    void testResolve_TrustedRemoteAddr_SpoofedChain_ReturnsRightmostUntrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        // client-falso (198.51.100.99), client-vero (203.0.113.50), proxy-fidato-2 (10.0.0.2)
        request.addHeader("X-Forwarded-For", "198.51.100.99, 203.0.113.50, 10.0.0.2");

        assertEquals("203.0.113.50", resolver.resolve(request));
    }

    @Test
    @DisplayName("f) remoteAddr fidato con solo proxy fidati in XFF effettua fallback su remoteAddr")
    void testResolve_TrustedRemoteAddr_AllTrustedInChain_ReturnsRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "10.0.0.1, 192.168.1.1");

        assertEquals("127.0.0.1", resolver.resolve(request));
    }

    @Test
    @DisplayName("g) remoteAddr fidato con token non-IP valido effettua fail-safe su remoteAddr")
    void testResolve_TrustedRemoteAddr_InvalidToken_FailsSafeToRemoteAddr() {
        MockHttpServletRequest requestScript = new MockHttpServletRequest();
        requestScript.setRemoteAddr("10.0.0.1");
        requestScript.addHeader("X-Forwarded-For", "\"><script>alert(1)</script>\"");
        assertEquals("10.0.0.1", resolver.resolve(requestScript));

        MockHttpServletRequest requestHostname = new MockHttpServletRequest();
        requestHostname.setRemoteAddr("127.0.0.1");
        requestHostname.addHeader("X-Forwarded-For", "evil.host, 192.168.1.1");
        assertEquals("127.0.0.1", resolver.resolve(requestHostname));
    }

    @Test
    @DisplayName("h) remoteAddr fidato con header XFF > 2048 caratteri effettua fail-safe su remoteAddr")
    void testResolve_TrustedRemoteAddr_OversizedHeader_FailsSafeToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        String oversized = "203.0.113.1, ".repeat(200) + "203.0.113.1";
        assertTrue(oversized.length() > 2048);
        request.addHeader("X-Forwarded-For", oversized);

        assertEquals("127.0.0.1", resolver.resolve(request));
    }

    @Test
    @DisplayName("i) più header X-Forwarded-For distinti nella stessa richiesta vengono concatenati correttamente")
    void testResolve_MultipleXffHeaders_ConcatenatedAndResolvedCorrectly() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.195, 10.0.0.1");
        request.addHeader("X-Forwarded-For", "192.168.1.1");

        assertEquals("203.0.113.195", resolver.resolve(request));
    }

    @Test
    @DisplayName("j) IPv6 loopback (::1) e indirizzi IPv6 fidati e non fidati gestiti correttamente")
    void testResolve_Ipv6Addresses_HandledCorrectly() {
        MockHttpServletRequest requestV6Loopback = new MockHttpServletRequest();
        requestV6Loopback.setRemoteAddr("::1");
        requestV6Loopback.addHeader("X-Forwarded-For", "2001:db8::1234, ::1");

        assertEquals("2001:db8::1234", resolver.resolve(requestV6Loopback));

        MockHttpServletRequest requestV6Untrusted = new MockHttpServletRequest();
        requestV6Untrusted.setRemoteAddr("2001:db8::9999");
        requestV6Untrusted.addHeader("X-Forwarded-For", "2001:db8::1234");

        assertEquals("2001:db8::9999", resolver.resolve(requestV6Untrusted));
    }

    @Test
    @DisplayName("k) Spazi e formattazione sporca in XFF gestiti con trim appropriato")
    void testResolve_DirtyFormattingAndWhitespace_CleanedProperly() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "   203.0.113.5   ,   10.0.0.1  ");

        assertEquals("203.0.113.5", resolver.resolve(request));
    }

    @Test
    @DisplayName("Verifica isTrustedProxy per indirizzi IPv4 e IPv6 RFC1918 / Loopback")
    void testIsTrustedProxy() {
        assertTrue(resolver.isTrustedProxy("127.0.0.1"));
        assertTrue(resolver.isTrustedProxy("127.255.255.255"));
        assertTrue(resolver.isTrustedProxy("10.0.0.1"));
        assertTrue(resolver.isTrustedProxy("10.254.1.99"));
        assertTrue(resolver.isTrustedProxy("172.16.0.1"));
        assertTrue(resolver.isTrustedProxy("172.31.255.255"));
        assertTrue(resolver.isTrustedProxy("192.168.0.1"));
        assertTrue(resolver.isTrustedProxy("192.168.254.10"));
        assertTrue(resolver.isTrustedProxy("::1"));

        assertFalse(resolver.isTrustedProxy("172.32.0.1"));
        assertFalse(resolver.isTrustedProxy("203.0.113.1"));
        assertFalse(resolver.isTrustedProxy("8.8.8.8"));
        assertFalse(resolver.isTrustedProxy("2001:db8::1"));
        assertFalse(resolver.isTrustedProxy(""));
        assertFalse(resolver.isTrustedProxy(null));
        assertFalse(resolver.isTrustedProxy("not-an-ip"));
    }

    @Test
    @DisplayName("Supporto porta IPv4 in XFF: 203.0.113.9:51234 risolto come 203.0.113.9")
    void testResolve_Ipv4WithPort_StripsPort() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "203.0.113.9:51234");

        assertEquals("203.0.113.9", resolver.resolve(request));
    }

    @Test
    @DisplayName("Supporto porta IPv6 tra parentesi quadre in XFF: [2001:db8::1]:443 risolto come 2001:db8::1")
    void testResolve_Ipv6WithPort_StripsPortAndBrackets() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "[2001:db8::1]:443");

        assertEquals("2001:db8::1", resolver.resolve(request));
    }

    @Test
    @DisplayName("Supporto porte multiple in catena XFF: 9.9.9.9:80, 203.0.113.9:51234 risolto con token più a destra")
    void testResolve_MultipleHopsWithPorts_ReturnsRightmostClient() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "9.9.9.9:80, 203.0.113.9:51234");

        assertEquals("203.0.113.9", resolver.resolve(request));
    }

    @Test
    @DisplayName("Hop fidato con porta in XFF viene scartato: 10.0.0.7:1234, 203.0.113.9:51234")
    void testResolve_TrustedHopWithPort_DiscardedAndReturnsClient() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        // Scansione da destra: 203.0.113.9:51234 è non fidato e viene restituito
        request.addHeader("X-Forwarded-For", "10.0.0.7:1234, 203.0.113.9:51234");

        assertEquals("203.0.113.9", resolver.resolve(request));

        // Se l'hop fidato è a destra del client: 203.0.113.9:51234, 10.0.0.7:1234
        MockHttpServletRequest request2 = new MockHttpServletRequest();
        request2.setRemoteAddr("10.0.0.5");
        request2.addHeader("X-Forwarded-For", "203.0.113.9:51234, 10.0.0.7:1234");

        assertEquals("203.0.113.9", resolver.resolve(request2));
    }

    @Test
    @DisplayName("Porta non valida in XFF (valore non numerico, vuota o fuori intervallo 0-65535) effettua fail-safe su remoteAddr")
    void testResolve_InvalidPort_FailsSafeToRemoteAddr() {
        MockHttpServletRequest requestOutOfRange = new MockHttpServletRequest();
        requestOutOfRange.setRemoteAddr("10.0.0.5");
        requestOutOfRange.addHeader("X-Forwarded-For", "1.2.3.4:99999");
        assertEquals("10.0.0.5", resolver.resolve(requestOutOfRange));

        MockHttpServletRequest requestAlphaPort = new MockHttpServletRequest();
        requestAlphaPort.setRemoteAddr("10.0.0.5");
        requestAlphaPort.addHeader("X-Forwarded-For", "1.2.3.4:abc");
        assertEquals("10.0.0.5", resolver.resolve(requestAlphaPort));

        MockHttpServletRequest requestEmptyPort = new MockHttpServletRequest();
        requestEmptyPort.setRemoteAddr("10.0.0.5");
        requestEmptyPort.addHeader("X-Forwarded-For", "1.2.3.4:");
        assertEquals("10.0.0.5", resolver.resolve(requestEmptyPort));
    }

    @Test
    @DisplayName("IPv6 senza parentesi quadre con più ':' non viene interpretato come indirizzo:porta")
    void testResolve_Ipv6WithoutBrackets_NotTreatedAsPort() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "2001:db8::1");

        assertEquals("2001:db8::1", resolver.resolve(request));
    }

    @Test
    @DisplayName("Configurazione personalizzata trusted-proxies: verifica restrizione a subnet specifica")
    void testCustomTrustedProxiesConfiguration() {
        AppConfig customConfig = new AppConfig();
        customConfig.getSecurity().setTrustedProxies("10.1.0.0/16");
        ClientIpResolver customResolver = new ClientIpResolver(customConfig);

        // 10.0.0.5 come remoteAddr NON è più un proxy fidato: XFF viene ignorato
        MockHttpServletRequest untrustedRemote = new MockHttpServletRequest();
        untrustedRemote.setRemoteAddr("10.0.0.5");
        untrustedRemote.addHeader("X-Forwarded-For", "203.0.113.9");
        assertEquals("10.0.0.5", customResolver.resolve(untrustedRemote));

        // 10.1.2.3 come remoteAddr è fidato: XFF viene analizzato
        MockHttpServletRequest trustedRemote = new MockHttpServletRequest();
        trustedRemote.setRemoteAddr("10.1.2.3");
        trustedRemote.addHeader("X-Forwarded-For", "203.0.113.9");
        assertEquals("203.0.113.9", customResolver.resolve(trustedRemote));
    }

    @Test
    @DisplayName("CIDR non valido nella configurazione viene scartato con warn senza compromettere gli altri CIDR validi")
    void testInvalidCidrInConfiguration_IgnoredGracefully() {
        AppConfig configWithInvalidCidr = new AppConfig();
        configWithInvalidCidr.getSecurity().setTrustedProxies("10.1.0.0/99,192.168.0.0/16");
        ClientIpResolver resilientResolver = new ClientIpResolver(configWithInvalidCidr);

        // 10.1.0.1 non è fidato perché il suo CIDR (/99) era malformato
        assertFalse(resilientResolver.isTrustedProxy("10.1.0.1"));

        // 192.168.1.1 rimane fidato perché il suo CIDR è valido
        assertTrue(resilientResolver.isTrustedProxy("192.168.1.1"));
    }

    @Test
    @DisplayName("IPv4-mapped IPv6 (::ffff:10.0.0.5) riconosciuto correttamente come proxy fidato")
    void testIsTrustedProxy_Ipv4MappedIpv6() {
        assertTrue(resolver.isTrustedProxy("::ffff:10.0.0.5"));
        assertTrue(resolver.isTrustedProxy("::ffff:127.0.0.1"));
        assertFalse(resolver.isTrustedProxy("::ffff:203.0.113.5"));
    }
}

