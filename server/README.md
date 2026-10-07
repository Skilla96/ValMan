# ValMan backend v0.5

Backend condiviso per l'app Android ValMan. Fornisce:

- account con ID dipendente e password scelta al primo accesso;
- ruoli Admin / Meccanico / Elettrico / Lettura;
- sincronizzazione local-first di impianti, guasti, interventi, turni, ferie/PAR, comunicazioni e consegne;
- audit delle operazioni server;
- Skilla Bot AI server-side (la chiave AI non entra nell'APK);
- web search per manuali tecnici quando il modello la ritiene necessaria;
- TTS neurale opzionale;
- endpoint upload predisposto per manuali/allegati e vector store opzionale.

## Avvio rapido con Docker

1. Copia `.env.example` in `.env`.
2. Imposta **JWT_SECRET** e **VALMAN_SETUP_CODE** con valori lunghi e non riutilizzati.
3. Se vuoi Skilla Bot online, imposta `OPENAI_API_KEY`.
4. Avvia con `docker compose up -d --build`.
5. Metti il servizio dietro HTTPS (reverse proxy/VPN aziendale). L'app rifiuta server HTTP.
6. Nell'app: **Amministrazione → Server & sincronizzazione**. Inserisci l'URL, il codice setup e la password amministratore, quindi inizializza il server.

## Primo avvio

`POST /api/bootstrap` funziona solo se la tabella utenti è vuota e richiede `VALMAN_SETUP_CODE`. Dopo il bootstrap l'amministratore riceve un token. Gli ID dipendente creati dall'admin non hanno password: al primo accesso il dipendente sceglie la propria password tramite `/api/auth/claim`.

## Sicurezza

- Non mettere mai `OPENAI_API_KEY`, `JWT_SECRET` o `VALMAN_SETUP_CODE` nell'APK.
- Pubblica il backend solo tramite HTTPS.
- Per uso aziendale reale, definire con IT/azienda dove possono essere conservati dati, foto, schemi e manuali e se possono essere inviati a servizi AI esterni.
- Skilla Bot è consultivo e non invia comandi a PLC, gru o macchine.
- Prima di produzione, predisporre backup del volume `/data`, monitoraggio e rotazione dei segreti.

## Dati sincronizzati in v0.5

La sincronizzazione scambia record strutturati e conserva una copia locale sul telefono. Gli URI Android `content://` di foto/documenti non vengono copiati su altri telefoni perché sono validi solo sul dispositivo originario. Il backend include già l'endpoint upload per la fase allegati condivisi; la v0.5 non sostituisce automaticamente tutti gli allegati locali con copie server.

## Skilla Bot

`/api/skilla` usa il contesto recuperato dal database ValMan. Se è configurato `OPENAI_VECTOR_STORE_ID`, può inoltre usare `file_search` sui manuali indicizzati. Per richieste di manuali o informazioni aggiornate può usare il tool Web Search. Le fonti Web vengono restituite all'app.
