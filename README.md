# 📡 iBeacon BLE Beacon Detector (with Tolerance) for Android

🇮🇹 Che tu ci creda o no, questo è il nome migliore che mi è venuto in mente.

🇬🇧 Believe it or not, that was the catchiest name I could come up with.

**This README is available in English and Italian. For English, scroll down.**

## 🇮🇹 Descrizione

Questa applicazione nasce da un’esigenza pratica: Triggerare una macro su **MacroDroid** quando mi avvicino o mi allontano da un beacon BLE.

MacroDroid offre già questa funzionalità, ma (al momento) è in **beta** e presenta una limitazione importante: **Non ha una tolleranza sulla perdita del segnale**

Questo significa che anche una perdita momentanea del segnale BLE può causare trigger indesiderati della macro

Questa app mira a risolvere questa problematica.

---

## ✅ Soluzione e use case

Nel mio caso avevo necessità di triggherare una macro su Macrodroid ogni volta che mi avvicinavo a pochi metri da uno specifico luogo. Quindi ho deciso di acquistare un dispositivo BLE su Amazon, e alla fine ho deciso per una board Arduino che può in realtà essere alimentata in autonomia ["NodeMCU ESP32 S Kit Bluetooth"](https://www.amazon.it/dp/B09PLBPBCC?ref=ppx_yo2ov_dt_b_fed_asin_title&th=1)
e con poche linee di codice che ci ho caricato dentro l'ho trasformata in un dispositivo BLE beacon con standard iBeacon.

Dato che Macrodroid, app che già usavo, offre in versione beta il trigger "Bluetooth Beacon" pensavo che il gioco fosse fatto, invece purtroppo come spesso accade, così non era.

Infatti il trigger Macrodroid non ha alcuna tolleranza nella perdita del segnale, anche stando nei pressi del beacon, con il cellulare appoggiato sul tavolo, capitava spesso che la macro fosse attivata in momenti casuali perché evidentemente in quel momento il check fatto da Macrodroid falliva per un pacchetto perso o per un segnale troppo debole, ma bastava nulla per triggerare la macro.

Pur provando varie soluzioni non c'era verso: la macro si avviava in momenti casuali, e allora l'unica soluzione che mi è venuta in mente è stata quella di sviluppare un'app che rilevasse i beacon BLE ma che introducesse il concetto di tolleranza di perdita del segnale, e che fosse in grado però di triggerare Macrodroid a sua volta.

Quindi, questa app introduce una tolleranza configurabile (in secondi) sia per:

- rilevamento del beacon (entrata)
- perdita del beacon (uscita)

Il beacon deve essere stabile per almeno N secondi prima di generare un evento. In corrispondenza degli eventi di entrata e uscita dal raggio del beacon, due distinti intent vengono mandati a livello di sistema Android, e possono essere rilevati e usati come trigger da Macrodroid.

---

## ⚙️ Funzionalità principali

- Scansione BLE continua in background
- Selezione manuale del beacon da monitorare
- Tolleranza configurabile (default: 10 secondi)
- Notifiche opzionali (attivabili/disattivabili)
- Invio di **Intent Android sempre attivi** (indipendentemente dalle notifiche)

---


## 🚀 Installare e usare l'app

Nella sezione release puoi scaricare l'apk e installarla direttamente sul tuo telefono, oppure puoi clonare la repo e buildare l'app da Android studio.

Una volta avviata l'app ti verranno chiesti alcuni permessi, purtroppo sono necessari per abilitare la scansione continua dei beacon su Android. Accetta tutti i permessi per assicurarti che l'app funzioni, compreso il permesso di utilizzo della posizione SEMPRE (non solo quando l'app è in uso).

ATTENZIONE: in base al produttore del tuo smartphone potrebbero esserci piu o meno limitazioni per quanto riguarda l'uso della batteria, quindi assicurati di abilitare il flag in impostazioni->App->BLE Beacon detector->utilizzo della batteria->consenti in background->senza limitazioni. Anche cosi purtroppo alcune limitazioni potrebbero venire dal produttore e potrebbero non essere sovrascrivibili.

Una volta dati tutti i permessi, premere il tasto "scan" per avviare la scansione dei dispositivi BLE iBeacon nelle vicinanze. Quando il dispositivo che si vuole monitorare appare nella lista, clicca su "select" per selezionarlo come dispositivo da monitorare. Da ora in poi l'app ti avviserà quando sarai nelle vicinanze o ti allontanerai dal dispositivo.
Inoltre è possibile selezionare se si vuole ricevere la notifica o meno, in ogni caso verrà triggherato l'intent di sistema.

---

## 📲 Intent generati

BEACON_VISIBLE  
BEACON_LOST  

ATTENZIONE: Gli intent vengono inviati anche se le notifiche sono disattivate!

---

## 🤖 Uso con MacroDroid

Usa il Trigger "Intent ricevuto" per far scattare la tua macro su BEACON_VISIBLE (il beacon è raggiungibile) oppure BEACON_LOST (il beacon non è più raggiungibile)


---



# 🇬🇧 Description

This application was created to meet a practical need: triggering a macro on MacroDroid when I move closer to or farther from a BLE beacon.

MacroDroid already offers this functionality, but (at the moment) it is in beta and has an important limitation: it does not provide any tolerance for signal loss.

This means that even a brief loss of the BLE signal can cause unwanted macro triggers.

This app aims to solve this issue.

---

## ✅ Solution

In my case, I needed to trigger a macro on MacroDroid every time I got within a few meters of a specific location. So I decided to purchase a BLE device on Amazon, and in the end I chose an Arduino board that can actually be powered independently ["NodeMCU ESP32 S Kit Bluetooth"](https://www.amazon.it/dp/B09PLBPBCC?ref=ppx_yo2ov_dt_b_fed_asin_title&th=1). With just a few lines of code, I turned it into a BLE beacon device using the iBeacon standard.

Since MacroDroid—an app I was already using—offers a "Bluetooth Beacon" trigger in beta, I thought the job was done. Unfortunately, as often happens, that wasn’t the case.

In fact, the MacroDroid trigger has no tolerance for signal loss. Even when staying close to the beacon, with the phone resting on the table, the macro would often be triggered at random times. This was likely because, at that moment, MacroDroid’s check failed due to a lost packet or a weak signal—but that was enough to trigger the macro.

Despite trying various solutions, there was no way around it: the macro kept triggering randomly. So the only solution I came up with was to develop an app that detects BLE beacons while introducing the concept of signal loss tolerance, and that can still trigger MacroDroid in turn.

So, this app introduces a configurable tolerance (in seconds) for both:

- beacon detection (entry)
- beacon loss (exit)

The beacon must remain stable for at least N seconds before generating an event. When entering or exiting the beacon’s range, two distinct intents are broadcast at the Android system level, which can then be detected and used as triggers by MacroDroid.

---

## ⚙️ Features

- Continuous BLE scanning
- Manual beacon selection
- Configurable tolerance
- Optional notifications
- Broadcast intents always sent

---

## 🚀 Install and use the app

In the Releases section, you can download the APK and install it directly on your phone, or you can clone the repository and build the app using Android Studio.

Once you launch the app, you will be asked to grant some permissions. Unfortunately, these are required to enable continuous beacon scanning on Android. Accept all permissions to ensure the app works properly, including the permission for location access ALWAYS (not just while the app is in use).

WARNING: depending on your smartphone manufacturer, there may be more or fewer limitations regarding battery usage. Make sure to enable the following setting:
Settings → Apps → BLE Beacon Detector → Battery usage → Allow background activity → No restrictions.
Even with these settings, some limitations may still be imposed by the manufacturer and may not be overridable.

Once all permissions are granted, press the "Scan" button to start scanning for nearby BLE iBeacon devices. When the device you want to monitor appears in the list, click "Select" to choose it as the device to monitor. From that point on, the app will notify you when you are near the device or when you move away from it.

You can also choose whether to receive notifications or not; in any case, the system intent will still be triggered.


---

## 🤖 Usage with macrodroid

Use the "Intent received" trigger to activate your macro on BEACON_VISIBLE (the beacon is reachable) or BEACON_LOST (the beacon is no longer reachable).


---

## 📲 Broadcast Intents

BEACON_VISIBLE  
BEACON_LOST  
