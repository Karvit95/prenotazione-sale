# Prenotazione Sale - Backend

Servizio backend Spring Boot 4.1.2 / Java 21 per la gestione e prenotazione delle sale riunioni dell'ente (Stazione Zoologica Anton Dohrn - SZN) con integrazione Microsoft Graph / Exchange Online.

## Deploy dietro reverse proxy

La proprietà di configurazione `app.security.trusted-proxies` (impostabile anche tramite variabile d'ambiente `APP_SECURITY_TRUSTED_PROXIES`) definisce la lista di CIDR (IPv4 e IPv6) corrispondenti ai reverse proxy fidati per la risoluzione sicura anti-spoofing dell'indirizzo IP del client reale tramite l'header `X-Forwarded-For` (XFF).

### Funzionamento e Valori di Default
- **Cosa fa**: Il componente `ClientIpResolver` verifica l'indirizzo IP della connessione TCP diretta (`remoteAddr`). Se `remoteAddr` appartiene a uno dei CIDR fidati, il backend analizza l'header `X-Forwarded-For` scorrendo la catena da destra verso sinistra, scartando gli hop fidati intermedi (e rimuovendo l'eventuale porta del client o del proxy, es. `IP:porta` o `[IPv6]:porta`) per individuare l'IP del client reale.
- **Valore di default**: `127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16`. Il default copre esclusivamente l'interfaccia di loopback locale e gli intervalli di indirizzi privati definiti da RFC 1918.
- **Configurazione in produzione**: In ambiente di produzione o staging posizionato dietro un reverse proxy, WAF o Application Gateway (es. Azure Application Gateway, Cloudflare, NGINX), è necessario impostare la proprietà con il CIDR reale o la lista di CIDR da cui provengono le connessioni del proxy.
- **Impatto di un proxy non incluso nei CIDR**: Se il reverse proxy si collega con un IP non censito nei CIDR di `app.security.trusted-proxies`, il resolver considererà la connessione non fidata e ignorerà del tutto l'header `X-Forwarded-For` come misura preventiva di fail-safe:
  1. **Rate Limiting**: Tutti gli utenti finali condivideranno lo stesso bucket di rate limit (associato all'IP del proxy), con il rischio concreto di bloccare indiscriminatamente tutti gli accessi legittimi al superamento della soglia (HTTP 429 Too Many Requests).
  2. **Audit Trail**: I registri di audit e sicurezza (NIS 2 / GDPR) certificheranno l'IP del proxy anziché l'effettivo indirizzo IP di provenienza del client.

### Esempio di Configurazione
Tramite variabile d'ambiente (formato kebab-case / relaxed binding di Spring Boot):
```bash
APP_SECURITY_TRUSTED_PROXIES=203.0.113.0/24,10.0.0.0/8
```

Oppure nel file `application.properties`:
```properties
app.security.trusted-proxies=203.0.113.0/24,10.0.0.0/8
```

