# KASE FIX Client

A small, well-documented **QuickFIX/J** client for connecting to the
**Kazakhstan Stock Exchange (KASE)** FIX order-entry gateway (MFIX Trade).

It supports the full set of order-management messages implemented so far:

| # | Function | Menu | FIX MsgType |
| --- | --- | --- | --- |
| 1 | **Logon** | automatic on start; option `8` to reconnect | `A` |
| 2 | **Place New Order** | option `2` | `D` (New Order - Single) |
| 3 | **Cancel Order** | option `3` | `F` (Order Cancel Request) |
| 4 | **Modify Order** | option `4` | `G` (Order Cancel/Replace Request) |
| 5 | **Mass Cancel** | option `5` | `q` (Order Mass Cancel Request) |
| 6 | **Logout** | option `7` | `5` |

Additional menu items: show connection status (`1`), list orders sent this
session (`6`), exit (`0`).

Everything that happens on the wire is logged to the **console** and to
**log files**. Each raw FIX line is preceded by a short parsed summary
(message type name + key fields) so you can tell at a glance what each
IN/OUT message is.

---

## 1. Requirements

These are already installed on your machine:

- **Java 21** (`java -version` → Temurin 21.0.1)
- **Maven 3.9+** (`mvn -version` → 3.9.16)

The FIX engine is **QuickFIX/J 3.0.1**, pulled in automatically by Maven.

---

## 2. Project layout

```
kase-fix-client/
├── pom.xml                                  # Maven build (deps, fat-jar, etc.)
├── run.sh                                   # one-command build + run helper
├── config/
│   └── kase-fix-client.cfg                  # >>> EDIT THIS: connection settings <<<
├── src/main/java/kz/kase/fixclient/
│   ├── KaseFixClient.java                   # main(): starts engine + menu
│   ├── KaseFixApplication.java              # FIX event handlers + parsed logging
│   └── OrderManager.java                    # builds/sends D, F, G, q messages
├── src/main/resources/
│   └── logback.xml                          # logging configuration
└── logs/                                    # created at runtime
    ├── kase-fix-client.log                  # human-readable app log
    └── fix-messages/                        # raw FIX message audit logs
```

---

## 3. How to run

### Option A – the helper script (easiest)

```bash
cd /Users/yerkinnn/Desktop/work/ngdem/kase/kase-fix-client
chmod +x run.sh        # only needed once
./run.sh
```

### Option B – Maven + java

```bash
mvn clean package
java -jar target/kase-fix-client.jar
```

### Option C – run during development

```bash
mvn exec:java
```

You can also point the app at a different config file:

```bash
java -jar target/kase-fix-client.jar /path/to/your.cfg
```

When it starts you will see a menu like this:

```
============== KASE FIX CLIENT  [NOT logged on] ==============
  1) Show connection status
  2) Place NEW order
  3) CANCEL an order
  4) MODIFY an order (Cancel/Replace)
  5) MASS CANCEL orders
  6) List orders sent this session
  7) LOGOUT (disconnect the FIX session)
  8) LOGON  (reconnect the FIX session)
  0) Exit the application
=================================================================
Choose an option:
```

Once the connection succeeds the header changes to `[LOGGED ON]` and you can
start trading.

---

## 4. >>> WHAT YOU MUST SUBSTITUTE WITH REAL KASE DATA <<<

Everything you need to change lives in **`config/kase-fix-client.cfg`** and is
marked with the word **`SUBSTITUTE`**. Open that file and search for it.

| Setting in `kase-fix-client.cfg` | What to put there |
| --- | --- |
| `SenderCompID` | **Your** CompID, assigned by KASE. |
| `TargetCompID` | KASE gateway's CompID (see MFIX Trade vs Trade Capture below). |
| `SocketConnectHost` | KASE FIX gateway hostname / IP. |
| `SocketConnectPort` | KASE FIX gateway TCP port. |
| `Password` | Your KASE FIX password (sent as tag 554 in the Logon). |
| `DefaultTradingSessionID` | Board / trading mode (SECBOARD) → tag 336. Pre-fills the New Order and Mass Cancel menus. |
| `DefaultAccount` | Your trading account → tag 1. Pre-fills the New Order and Mass Cancel menus. |
| `BeginString` | FIX version. Defaults to `FIX.4.4`. Change only if KASE says so. |
| `HeartBtInt` | Heartbeat interval; match KASE's requirement (often `30`). |
| `SenderSubID` / `TargetSubID` | Optional – uncomment only if KASE requires them. |
| `SocketUseSSL` | Uncomment if KASE requires a TLS/SSL connection. |

### MFIX Trade vs MFIX Trade Capture

KASE exposes two separate FIX gateways on the test system:

| Gateway | Purpose | Port | TargetCompID |
| --- | --- | --- | --- |
| **MFIX Trade** | Order entry (place / cancel / modify orders) | 9215 | `MFIXTradeIDFond` |
| **MFIX Trade Capture** | Trade reports (receive fills / executions) | 9214 | `MFIXTradeCaptureID` |

This client is built for **MFIX Trade** (order entry). Point `SocketConnectPort`
and `TargetCompID` at the Trade gateway, not Trade Capture.

**If KASE requires a FIX version other than 4.4:** change `BeginString` in the
config **and** swap the message library in `pom.xml`
(`quickfixj-messages-fix44` → e.g. `quickfixj-messages-fix42`), then rebuild.

**If KASE provides a custom data dictionary** (a `*.xml` file describing their
exact message layout): drop it into `config/`, then in the config set
`DataDictionary=config/THEIR_FILE.xml`.

---

## 5. How each functionality works

### Logon
Handled automatically by the engine on startup. Our code attaches the
`Password` (and optional `Username`) to the outgoing Logon in
`KaseFixApplication.toAdmin(...)`. A successful logon prints a big
`LOGON SUCCESSFUL` banner. Use menu option `8` to log on again after a logout.

> **Note:** only one active session per `SenderCompID` is allowed. If you see
> immediate disconnects on logon, make sure no other client (desktop terminal,
> second instance of this app) is logged in with the same CompID.

### Place New Order (option 2)
You are prompted for symbol (SECCODE), board (TradingSessionID / SECBOARD),
account, side (B/S), quantity and an optional limit price (leave empty for a
**market** order). The app builds a `NewOrderSingle` in
`OrderManager.sendNewOrder(...)` with all KASE-mandatory fields:

- `Account (1)`, `NoTradingSessions (386)` + `TradingSessionID (336)`
- `Symbol (55)`, `Side (54)`, `TransactTime (60)` in UTC
- `OrdType (40)`, `OrderQty (38)`, `Price (44)` (0 for market)

The **ClOrdID** is printed so you can cancel or modify the order later. The
exchange reply arrives as an **ExecutionReport** and is explained in plain
language in the log.

### Cancel Order (option 3)
Pick the ClOrdID of an order you placed during this run. The app builds an
`OrderCancelRequest` in `OrderManager.sendCancel(...)`, referencing the
original order by `OrigClOrdID (41)` and, when known, the exchange `OrderID
(37)`. If the cancel is rejected, the reason is logged clearly.

### Modify Order – Cancel/Replace (option 4)
Change the **price** and/or **quantity** of a live order without cancelling it
yourself first. Pick the ClOrdID, then enter new values (press Enter to keep the
current value). The app sends an `OrderCancelReplaceRequest` (MsgType `G`) per
KASE spec section 4.2.4. KASE atomically replaces the old order and assigns a
new **ClOrdID** to the amended order.

### Mass Cancel (option 5)
Cancel many orders at once with a single `OrderMassCancelRequest` (MsgType `q`).
Choose one of the KASE-documented scopes (spec section 4.2.5):

| Scope | MassCancelRequestType (530) | Extra fields |
| --- | --- | --- |
| ALL my orders | `7` | (none) |
| by SECURITY (board + symbol) | `1` | `TradingSessionID (336)` + `Symbol (55)` |
| BUY orders only | `7` | `Side (54) = 1` |
| SELL orders only | `7` | `Side (54) = 2` |
| by ACCOUNT | `7` | `Account (1)` |
| by USER | `7` | `NoPartyIDs (453)=1` group: `PartyID (448)`, `PartyIDSource (447)=D`, `PartyRole (452)=12` |
| by FIRM | `7` | `NoPartyIDs (453)=1` group: `PartyID (448)`, `PartyIDSource (447)=D`, `PartyRole (452)=1` |

`ClOrdID (11)` and `TransactTime (60)` are always sent regardless of scope.
KASE replies with an **Order Mass Cancel Report** (MsgType `r`), which the
client logs in readable form (response code, affected order count, reject reason
if any). Individual cancelled orders may also arrive as **ExecutionReports**
(status CANCELLED).

> Mass cancel targets orders on the exchange, not just orders placed by this
> client during the current run.

### Logout (option 7)
Sends a FIX `Logout` and disconnects. The app keeps running so you can log on
again (option 8) or exit (option 0).

> **Note:** this simple client only remembers orders placed during the **current**
> run (in memory). That keeps the example easy to follow.

---

## 6. Logging

Two layers of logging help you follow every FIX conversation:

### Parsed summary (human-readable)
Printed just above each raw FIX line:

```
OUT -> NEW ORDER | seq=3 | ClOrdID=ORD-... | BUY | qty=1 | symbol=AIRA | price=500 | LIMIT
--> APP OUT  : 8=FIX.4.4|9=166|35=D|34=3|49=...|...
IN  <- EXECUTION REPORT | seq=4 | ClOrdID=ORD-... | status=REJECTED | text=...
<-- APP IN   : 8=FIX.4.4|9=366|35=8|34=4|49=...|...
IN  <- HEARTBEAT | seq=18
<-- ADMIN IN : 8=FIX.4.4|9=67|35=0|34=18|49=...|...
```

### Raw FIX lines
The exact wire format, with the invisible `0x01` field separator shown as `|`:

```
--> ADMIN OUT : ...   (we are sending a session message, e.g. Logon)
<-- ADMIN IN  : ...   (server sent us a session message)
--> APP OUT   : ...   (we are sending a business message, e.g. an order)
<-- APP IN    : ...   (server sent us a business message, e.g. ExecutionReport)
```

### Where to look

| Location | What it contains |
| --- | --- |
| **Console** | Live trace of parsed summaries + raw FIX lines. |
| `logs/kase-fix-client.log` | Same trace, saved to disk (rotated daily). |
| `logs/fix-messages/` | Raw FIX audit log written by QuickFIX/J (share with KASE support). |

KASE sends Cyrillic text fields (e.g. `Text` tag 58) in **Windows-1251**. The
client sets `CharsetSupport.setCharset("windows-1251")` at startup so these
fields decode correctly.

---

## 7. Quick troubleshooting

| Symptom | Likely cause / fix |
| --- | --- |
| Stuck at `NOT logged on`, connection retries | Host/port/credentials are still placeholders, or KASE gateway not reachable / not in trading hours. |
| `Server LOGOUT reason: ...` in the log | KASE rejected the logon – read the reason text (bad credentials, wrong CompID, duplicate login). |
| Immediate disconnect after logon | Another session is already active with the same `SenderCompID` (desktop terminal, second client instance). |
| `35=j` Business Message Reject: "Unsupported message type." | Missing mandatory fields, wrong gateway (Trade Capture instead of Trade), or FIX entitlement not provisioned for your CompID. |
| Orders rejected with a `Text` message | KASE didn't like a field (symbol, board, account, price tick, lot size…). Read the `Text` in the ExecutionReport. |
| Garbled Cyrillic in `Text (58)` | Charset mismatch – ensure the app starts with `windows-1251` (done automatically). Terminal font may still affect display. |
| `Missing ConnectionType` / config errors | Make sure you didn't break the `.cfg` format; comments start with `#`. |
