# Al Borgo – app Android (consultazione)

App che mostra il portale di consultazione del bar **Al Borgo** (Cruscotto, Acquisti,
Prodotti, Fornitori, Chiedi, Fatture) dentro una WebView. Funziona **solo dentro la rete
Benazzi**, dove il portale e raggiungibile (es. `http://192.168.1.34/ilborghetto/`).

- Ricorda l'accesso tra un'apertura e l'altra (cookie salvati).
- Invia l'intestazione `X-Borghetto-Device`: nella pagina **Utenti e azioni** del portale
  si vede da quale tablet arriva ogni consultazione.
- L'indirizzo del server si chiede al primo avvio e si cambia dal menu (tre puntini).

## Impostare l'indirizzo del server

Due possibilita:
1. Alla prima apertura l'app chiede l'indirizzo del portale: scrivilo e premi OK.
2. In alternativa si puo cambiare il valore predefinito in
   `app/src/main/java/it/benazzi/alborgo/MainActivity.kt` → `DEFAULT_URL`.

## Come ottenere l'APK

### A) Con Android Studio (consigliato)
1. Installa **Android Studio** (include JDK e Android SDK).
2. `File → Open…` e seleziona questa cartella.
3. Attendi il primo sync (scarica da solo quello che serve).
4. `Build → Build Bundle(s) / APK(s) → Build APK(s)`.
5. L'APK e in `app/build/outputs/apk/debug/app-debug.apk`.

### B) In automatico su GitHub (senza installare nulla)
1. Crea un repository su GitHub e carica questa cartella.
2. Apri la scheda **Actions**: la build parte da sola a ogni push
   (o lanciala a mano con **Run workflow**).
3. Al termine scarica **AlBorgo-APK** dalla sezione *Artifacts*: dentro c'e `app-debug.apk`.

## Installare l'APK sul tablet
1. Copia `app-debug.apk` sul tablet.
2. Aprilo: Android chiedera di consentire l'installazione da "origini sconosciute" → consenti.
3. Apri **Al Borgo**, inserisci l'indirizzo del portale, accedi con l'utente di consultazione.

> Nota: l'app usa HTTP in chiaro perche il portale gira in locale sulla LAN.
> Quando sara pronto l'accesso dall'esterno (Porta Borghetto), bastera cambiare
> l'indirizzo del server nell'app, senza ricompilare.
