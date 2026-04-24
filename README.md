# 📡 BLE Beacon Detector (with Tolerance)

## IT: Descrizione

Questa applicazione nasce da un’esigenza pratica:

👉 Triggerare una macro su **MacroDroid** quando mi avvicino o mi allontano da un beacon BLE.

MacroDroid offre già questa funzionalità, ma (al momento) è in **beta** e presenta una limitazione importante:

❗ **Non ha una tolleranza sulla perdita del segnale**

Questo significa che anche una perdita momentanea del segnale BLE può causare trigger indesiderati della macro

---

## ✅ Soluzione

Questa app introduce una **tolleranza configurabile (in secondi)** sia per:

- rilevamento del beacon (entrata)
- perdita del beacon (uscita)

👉 Il beacon deve essere stabile per almeno *N secondi* prima di generare un evento.

---

## ⚙️ Funzionalità principali

- 📡 Scansione BLE continua in background
- 🎯 Selezione manuale del beacon da monitorare
- ⏱️ Tolleranza configurabile (default: 10 secondi)
- 🔔 Notifiche opzionali (attivabili/disattivabili)
- 🔄 Invio di **Intent Android sempre attivi** (indipendentemente dalle notifiche)

---

## 📲 Intent generati

BEACON_VISIBLE  
BEACON_LOST  

👉 Anche se le notifiche sono disattivate

---

## 🤖 Uso con MacroDroid

Trigger: Intent ricevuto

Azione:
BEACON_VISIBLE  
oppure  
BEACON_LOST  

---

## COME INSTALLARE

Nella sezione release puoi scaricare l'apk e installarla direttamente sul tuo telefono, oppure puoi clonare la repo e buildare l'app da Android studio.

---


# EN Description

This app was created to solve a practical problem:

👉 Trigger a macro in **MacroDroid** when entering or leaving a BLE beacon range.

MacroDroid already supports this feature, but:

❗ It lacks signal tolerance

---

## ✅ Solution

Adds configurable tolerance in seconds for stable detection.

---

## ⚙️ Features

- Continuous BLE scanning
- Manual beacon selection
- Configurable tolerance
- Optional notifications
- Broadcast intents always sent

---

## 📲 Broadcast Intents

BEACON_VISIBLE  
BEACON_LOST  

---

## 🤖 Usage

Use "Intent Received" trigger in MacroDroid.

---

## HOW TO INSTALL

In "releases" you should be able to download the apk and install it on your device, or you can clone the repository and build the app from android studio.
