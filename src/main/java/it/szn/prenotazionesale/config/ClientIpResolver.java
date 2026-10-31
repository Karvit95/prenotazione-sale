package it.szn.prenotazionesale.config;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Risolutore sicuro dell'indirizzo IP del client per protezione da spoofing
 * dell'header X-Forwarded-For (XFF).
 * <p>
 * Algoritmo di estrazione:
 * 1. Se remoteAddr non proviene da un reverse proxy fidato (definito da CIDR in AppConfig),
 *    l'header XFF viene completamente ignorato e viene restituito remoteAddr.
 * 2. Se remoteAddr è un proxy fidato, si analizza la catena XFF scorrendo da DESTRA verso SINISTRA.
 *    Ogni hop intermedio fidato viene scartato; il primo IP letterale valido NON fidato
 *    rappresenta il client reale.
 * 3. Se la catena contiene token non-IP (possibili injection o hostname non risolti) o supera
 *    il limite massimo di sicurezza (2048 caratteri), la risoluzione si interrompe e
 *    restituisce remoteAddr (fail-safe).
 * 4. La validazione dei letterali IP (IPv4 e IPv6) e il matching CIDR sono eseguiti senza
 *    librerie esterne e senza risoluzione DNS.
 */
@Slf4j
@Component
public class ClientIpResolver {

    private static final String DEFAULT_TRUSTED_PROXIES =
            "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";
    private static final int MAX_XFF_LENGTH = 2048;
    public static final String UNKNOWN_IP = "UNKNOWN";

    private final AppConfig appConfig;
    private List<CidrMatcher> matchers = Collections.emptyList();

    @Autowired
    public ClientIpResolver(AppConfig appConfig) {
        this.appConfig = appConfig;
        initMatchers();
    }

    public ClientIpResolver() {
        this(null);
    }

    @PostConstruct
    public void initMatchers() {
        String configuredProxies = null;
        if (appConfig != null && appConfig.getSecurity() != null) {
            configuredProxies = appConfig.getSecurity().getTrustedProxies();
        }
        if (configuredProxies == null || configuredProxies.isBlank()) {
            configuredProxies = DEFAULT_TRUSTED_PROXIES;
        }

        List<CidrMatcher> parsed = new ArrayList<>();
        String[] cidrTokens = configuredProxies.split(",");
        for (String token : cidrTokens) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                CidrMatcher matcher = parseCidr(trimmed);
                if (matcher != null) {
                    parsed.add(matcher);
                } else {
                    log.warn("CIDR non valido nella configurazione trusted-proxies: '{}'", trimmed);
                }
            }
        }
        this.matchers = Collections.unmodifiableList(parsed);
    }

    /**
     * Risolve l'IP del client reale applicando la verifica dei proxy fidati
     * e la scansione sicura anti-spoofing da destra verso sinistra.
     *
     * @param request richiesta HTTP (può essere null)
     * @return IP client affidabile o "UNKNOWN" se non determinabile
     */
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN_IP;
        }

        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return UNKNOWN_IP;
        }
        remoteAddr = remoteAddr.trim();

        // Se il client si collega direttamente o tramite proxy non fidato, ignora del tutto XFF
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        Enumeration<String> headers = request.getHeaders("X-Forwarded-For");
        if (headers == null || !headers.hasMoreElements()) {
            return remoteAddr;
        }

        StringBuilder combinedBuilder = new StringBuilder();
        while (headers.hasMoreElements()) {
            String headerVal = headers.nextElement();
            if (headerVal != null && !headerVal.isBlank()) {
                if (combinedBuilder.length() > 0) {
                    combinedBuilder.append(",");
                }
                combinedBuilder.append(headerVal);
            }
        }

        String combinedXff = combinedBuilder.toString().trim();
        if (combinedXff.isEmpty()) {
            return remoteAddr;
        }

        if (combinedXff.length() > MAX_XFF_LENGTH) {
            log.warn("Header X-Forwarded-For supera la lunghezza massima consentita di {} caratteri (len={}). Fallback su remoteAddr: {}",
                    MAX_XFF_LENGTH, combinedXff.length(), remoteAddr);
            return remoteAddr;
        }

        String[] tokens = combinedXff.split(",");
        for (int i = tokens.length - 1; i >= 0; i--) {
            String token = tokens[i].trim();
            if (token.isEmpty()) {
                continue;
            }

            // Normalizza rimuovendo eventuali parentesi quadre attorno a IPv6 (es. [::1])
            String cleanToken = token;
            if (cleanToken.startsWith("[") && cleanToken.endsWith("]") && cleanToken.length() > 2) {
                cleanToken = cleanToken.substring(1, cleanToken.length() - 1).trim();
            }

            byte[] ipBytes = parseIpToBytes(cleanToken);
            if (ipBytes == null) {
                log.warn("Token non valido come letterale IP in X-Forwarded-For: '{}'. Interruzione catena e fallback su remoteAddr: {}",
                        token, remoteAddr);
                return remoteAddr;
            }

            if (isTrustedProxy(ipBytes)) {
                // Hop intermedio fidato: scarta e continua la scansione verso sinistra
                continue;
            }

            // Primo IP valido e non fidato trovato da destra: è il client reale
            return cleanToken;
        }

        // Tutti gli hop nella catena erano proxy fidati
        return remoteAddr;
    }

    /**
     * Verifica se un indirizzo IP ricade nei CIDR dei reverse proxy fidati.
     *
     * @param ip stringa IP (IPv4 o IPv6)
     * @return true se l'indirizzo appartiene a un proxy fidato
     */
    public boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String cleanIp = ip.trim();
        if (cleanIp.startsWith("[") && cleanIp.endsWith("]") && cleanIp.length() > 2) {
            cleanIp = cleanIp.substring(1, cleanIp.length() - 1).trim();
        }
        byte[] bytes = parseIpToBytes(cleanIp);
        return isTrustedProxy(bytes);
    }

    private boolean isTrustedProxy(byte[] ipBytes) {
        if (ipBytes == null) {
            return false;
        }
        for (CidrMatcher matcher : matchers) {
            if (matcher.matches(ipBytes)) {
                return true;
            }
        }
        return false;
    }

    static byte[] parseIpToBytes(String ip) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }
        if (ip.indexOf(':') == -1) {
            return parseIpv4(ip);
        } else {
            return parseIpv6(ip);
        }
    }

    private static byte[] parseIpv4(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) {
                return null;
            }
            for (int c = 0; c < p.length(); c++) {
                char ch = p.charAt(c);
                if (ch < '0' || ch > '9') {
                    return null;
                }
            }
            if (p.length() > 1 && p.charAt(0) == '0') {
                return null; // leading zeros non ammessi per evitare ambiguità ottale
            }
            int val = Integer.parseInt(p);
            if (val < 0 || val > 255) {
                return null;
            }
            bytes[i] = (byte) val;
        }
        return bytes;
    }

    private static byte[] parseIpv6(String ip) {
        byte[] embeddedV4 = null;
        String v6Part = ip;
        int lastColon = ip.lastIndexOf(':');
        if (lastColon != -1 && ip.indexOf('.', lastColon) != -1) {
            String v4Part = ip.substring(lastColon + 1);
            embeddedV4 = parseIpv4(v4Part);
            if (embeddedV4 == null) {
                return null;
            }
            v6Part = ip.substring(0, lastColon);
        }

        int targetHextets = (embeddedV4 != null) ? 6 : 8;
        int dcIndex = v6Part.indexOf("::");
        String[] leftParts;
        String[] rightParts;

        if (dcIndex != -1) {
            if (v6Part.indexOf("::", dcIndex + 2) != -1) {
                return null;
            }
            String leftStr = v6Part.substring(0, dcIndex);
            String rightStr = v6Part.substring(dcIndex + 2);

            leftParts = leftStr.isEmpty() ? new String[0] : leftStr.split(":", -1);
            rightParts = rightStr.isEmpty() ? new String[0] : rightStr.split(":", -1);

            for (String p : leftParts) {
                if (!isValidHextet(p)) {
                    return null;
                }
            }
            for (String p : rightParts) {
                if (!isValidHextet(p)) {
                    return null;
                }
            }

            int givenParts = leftParts.length + rightParts.length;
            int zerosNeeded = targetHextets - givenParts;
            if (zerosNeeded < 1) {
                return null;
            }

            byte[] result = new byte[16];
            int bytePos = 0;
            for (String p : leftParts) {
                int val = Integer.parseInt(p, 16);
                result[bytePos++] = (byte) (val >> 8);
                result[bytePos++] = (byte) (val & 0xFF);
            }
            for (int z = 0; z < zerosNeeded; z++) {
                result[bytePos++] = 0;
                result[bytePos++] = 0;
            }
            for (String p : rightParts) {
                int val = Integer.parseInt(p, 16);
                result[bytePos++] = (byte) (val >> 8);
                result[bytePos++] = (byte) (val & 0xFF);
            }
            if (embeddedV4 != null) {
                System.arraycopy(embeddedV4, 0, result, 12, 4);
            }
            return result;
        } else {
            String[] parts = v6Part.split(":", -1);
            if (parts.length != targetHextets) {
                return null;
            }
            for (String p : parts) {
                if (!isValidHextet(p)) {
                    return null;
                }
            }
            byte[] result = new byte[16];
            int bytePos = 0;
            for (String p : parts) {
                int val = Integer.parseInt(p, 16);
                result[bytePos++] = (byte) (val >> 8);
                result[bytePos++] = (byte) (val & 0xFF);
            }
            if (embeddedV4 != null) {
                System.arraycopy(embeddedV4, 0, result, 12, 4);
            }
            return result;
        }
    }

    private static boolean isValidHextet(String part) {
        if (part == null || part.isEmpty() || part.length() > 4) {
            return false;
        }
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            boolean isHex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!isHex) {
                return false;
            }
        }
        return true;
    }

    private static CidrMatcher parseCidr(String cidr) {
        String trimmed = cidr.trim();
        int slash = trimmed.indexOf('/');
        String ipStr = (slash != -1) ? trimmed.substring(0, slash).trim() : trimmed;
        if (ipStr.startsWith("[") && ipStr.endsWith("]") && ipStr.length() > 2) {
            ipStr = ipStr.substring(1, ipStr.length() - 1).trim();
        }
        byte[] netBytes = parseIpToBytes(ipStr);
        if (netBytes == null) {
            return null;
        }

        int prefix;
        if (slash != -1) {
            try {
                prefix = Integer.parseInt(trimmed.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            prefix = (netBytes.length == 4) ? 32 : 128;
        }

        int maxPrefix = (netBytes.length == 4) ? 32 : 128;
        if (prefix < 0 || prefix > maxPrefix) {
            return null;
        }

        return new CidrMatcher(netBytes, prefix);
    }

    private static class CidrMatcher {
        private final byte[] networkBytes;
        private final int prefixLength;

        CidrMatcher(byte[] rawNetBytes, int prefixLength) {
            byte[] normNet = normalizeToIpv4IfMapped(rawNetBytes);
            int maxBits = normNet.length * 8;
            int effectivePrefix = Math.min(prefixLength, maxBits);

            byte[] maskedNet = normNet.clone();
            int fullBytes = effectivePrefix / 8;
            int remainingBits = effectivePrefix % 8;
            if (remainingBits > 0 && fullBytes < maskedNet.length) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                maskedNet[fullBytes] = (byte) (maskedNet[fullBytes] & mask);
            }
            for (int b = fullBytes + (remainingBits > 0 ? 1 : 0); b < maskedNet.length; b++) {
                maskedNet[b] = 0;
            }

            this.networkBytes = maskedNet;
            this.prefixLength = effectivePrefix;
        }

        boolean matches(byte[] ipBytes) {
            if (ipBytes == null) {
                return false;
            }
            byte[] normIp = normalizeToIpv4IfMapped(ipBytes);
            if (normIp.length != networkBytes.length) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (normIp[i] != networkBytes[i]) {
                    return false;
                }
            }

            if (remainingBits > 0 && fullBytes < networkBytes.length) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((normIp[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        }
    }

    private static byte[] normalizeToIpv4IfMapped(byte[] bytes) {
        if (bytes != null && bytes.length == 16) {
            boolean isMapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) {
                    isMapped = false;
                    break;
                }
            }
            if (isMapped && bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF) {
                byte[] ipv4 = new byte[4];
                System.arraycopy(bytes, 12, ipv4, 0, 4);
                return ipv4;
            }
        }
        return bytes;
    }
}

