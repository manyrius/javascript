# SIB SmartHub per iPhone e iPad

Progetto iOS 16 o successivo. Usa il QR JSON con campi u e t della pagina Dispositivi, lo stesso servizio Artsdomus e la stessa approvazione dell'app Android. Le società disponibili sono determinate dal server tramite la sessione MobileReader. Il codice dell'app e la pipeline sono predisposti. La compilazione Apple, la firma e il collaudo sul dispositivo devono risultare completati prima della distribuzione.

## Installazione privata Ad Hoc

1. Attivare un account Apple Developer Program intestato al soggetto che distribuisce SIB. Apple indica 99 USD l'anno o il prezzo locale al momento dell'iscrizione. Ogni destinatario usa il proprio normale iPhone, mentre la firma è gestita dall'account dello sviluppatore.
2. Usare un Mac con Xcode 16 o successivo e installare XcodeGen con `brew install xcodegen`. La compilazione senza firma è predisposta anche in GitHub Actions nel workflow `Verifica SIB iPhone e iPad`.
3. Registrare gli UDID degli iPhone e iPad nell'account Apple Developer. Ad Hoc prevede un massimo di 100 dispositivi per famiglia di prodotto e anno di iscrizione. Collegando il dispositivo al Mac, Xcode mostra l'identificativo in Window > Devices and Simulators.
4. Dal Terminale, nella cartella ios, eseguire `bash prepara-mac.command`. In Xcode aprire Signing & Capabilities e scegliere lo stesso Team su SIBSmartHub e SIBTunnel. Verificare Network Extensions > Packet Tunnel e App Groups > group.it.benazzi.smarthub.ios per entrambi i target. I Bundle ID devono essere registrabili dal vostro Team. Se già occupati, aggiornare anche Settings.swift e project.yml.
5. Per una prima prova con cavo selezionare l'iPhone come destinazione in Xcode e premere Run. Quando richiesto, abilitare Modalità sviluppatore sul dispositivo e completare le autorizzazioni Apple.
6. Per generare il pacchetto Ad Hoc, dal Terminale eseguire `APPLE_TEAM_ID=IL_TUO_TEAM_ID bash esporta-privata.command`. Xcode deve già essere connesso all'account Apple e autorizzato a generare i profili per i dispositivi registrati. Il file .ipa firmato viene creato in build/privata.
7. Sul Mac aprire Apple Configurator, collegare l'iPhone, autorizzare il computer e aggiungere il file .ipa al dispositivo. Ripetere per gli altri dispositivi registrati. La validità dipende dai certificati e dai profili di firma; pianificare il rinnovo prima della scadenza. Per un nuovo UDID occorre rigenerare il profilo e il pacchetto.
8. Aprire SIB SmartHub, assegnare un nome riconoscibile e scansionare il QR creato dal portale. L'amministratore approva e assegna le società. L'app controlla l'approvazione ogni quattro secondi. Accettare la richiesta iOS di aggiungere la configurazione VPN. Il portale si apre con il ruolo Consultatore.

Una compilazione senza firma prodotta da GitHub serve a controllare il codice. Per ottenere un IPA installabile occorrono il Team Apple e i profili di firma. TestFlight può essere usato per la fase di prova dopo aver configurato App Store Connect; la distribuzione Ad Hoc sopra descritta usa dispositivi registrati e un pacchetto privato.

## Compatibilità e comportamento

- L'API pubblica è fissata a https://www.artsdomus.com/alborgo/stradioltre.ashx. Il QR accetta anche il dominio artsdomus.com e normalizza la connessione sul dominio www. Nessuna chiave amministratore viene inserita nell'app.
- La chiave RSA 2048 e l'identificativo assegnato restano nel Portachiavi del dispositivo. I tentativi ripetuti riusano la stessa chiave per consentire al server di riconoscere il dispositivo. La chiave RSA è protetta dal Portachiavi; questa implementazione non usa Secure Enclave, che non offre RSA.
- La busta usa RSA-OAEP SHA256, HMAC-SHA256 e AES-CBC PKCS7, come il client Android. Il profilo OpenVPN viene decifrato per avviare l'estensione e passato attraverso un messaggio temporaneo. Le preferenze VPN salvate contengono il riferimento all'estensione.
- Il portale è fissato a http://192.168.1.34/ilborghetto/. La sessione passa da mobile-auth/challenge e mobile-auth/session. Il server impone i permessi e l'accesso alle società, anche sui collegamenti diretti.
- Il tunnel instrada soltanto 192.168.1.34/32. Su iPhone personali questa rotta riguarda il dispositivo e può essere usata da altre app mentre il tunnel è attivo. L'isolamento per singola app richiede una configurazione Apple gestita; per limitare i servizi raggiungibili impostare anche le regole sul gateway VPN. Le autorizzazioni del portale restano indispensabili.
- L'app richiede lo schermo intero su iPad, interrompe il tunnel quando perde il primo piano e invia una presenza ogni cinque secondi. L'estensione chiude il tunnel dopo circa 20–25 secondi senza presenza, anche se l'app viene terminata. Il comportamento su iPhone reale, blocco schermo, finestre di consenso e iPad va collaudato.
- La verifica della revoca avviene ogni 30 secondi durante la consultazione. La sessione di consultazione viene rinnovata dopo 12 minuti. Gli errori di verifica interrompono il tunnel e richiedono un nuovo tentativo.
- La configurazione ATS consente il portale HTTP interno già usato da Android. Le richieste di provisioning restano HTTPS. Il traffico HTTP al server passa nel tunnel quando la connessione è attiva.
- Il profilo VPN deve contenere certificato e chiave client inline e autenticazione a certificato. Profili con auth-user-pass o opzioni non supportate vengono segnalati. La compatibilità con la vostra jolly deve essere verificata sul dispositivo.

## Dipendenza VPN e distribuzione

Il prototipo integra TunnelKit al commit 5cd1c64be666dd94ea65afcf3c5684fa2c87a925, compatibile con Swift 5.9. TunnelKit è dichiarato dismesso dal manutentore. Prima dell'uso produttivo occorre valutare manutenzione e migrazione al successore Partout, che ha API e requisiti di compilazione diversi. Il presente progetto consente il collaudo del protocollo esistente.

TunnelKit è GPLv3 con permesso aggiuntivo per Apple App Store descritto dal suo autore. La distribuzione dei binari deve rispettare le licenze delle dipendenze, inclusa la disponibilità dei sorgenti corrispondenti. Per distribuzione proprietaria occorre verificare la licenza appropriata con il manutentore. Sono presenti i testi della licenza e del permesso aggiuntivo in ThirdParty. Il modulo LZO non è incluso.

## Collaudo prima della consegna

Eseguire il workflow iOS o `xcodebuild test` su un simulatore iPhone. I test coprono QR, confini del portale, esportazione della chiave RSA e OAEP SHA256. Sul dispositivo fisico verificare inoltre attivazione, riapertura senza duplicati, approvazione, accesso con una/due/tre società, diniego pagine amministrative, revoca, rientro dall'inattività, assenza di rete e spegnimento del tunnel a blocco schermo. Verificare anche le regole del gateway per i servizi raggiungibili.

Riferimenti ufficiali

- https://developer.apple.com/help/account/membership/program-enrollment
- https://developer.apple.com/documentation/xcode/distributing-your-app-to-registered-devices/
- https://developer.apple.com/help/account/devices/devices-overview
- https://developer.apple.com/documentation/xcode/configuring-network-extensions/
- https://github.com/partout-io/tunnelkit
