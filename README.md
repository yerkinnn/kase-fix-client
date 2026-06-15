# KASE FIX Client

A small, well-documented **QuickFIX/J** client for connecting to the
**Kazakhstan Stock Exchange (KASE)** FIX order-entry gateway.

It supports the four things you asked for:

1. **Logon** – happens automatically when the app starts (and via menu option `6`).
2. **Place New Order** – menu option `2` (sends a FIX `NewOrderSingle`, MsgType `D`).
3. **Cancel Order** – menu option `3` (sends a FIX `OrderCancelRequest`, MsgType `F`).
4. **Logout** – menu option `5` (sends a FIX `Logout`, MsgType `5`).

Everything that happens on the wire is logged, both to the **console** and to
log files, so you can follow the whole conversation step by step.

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
│   ├── KaseFixApplication.java              # FIX event handlers + logging
│   └── OrderManager.java                    # builds/sends New Order & Cancel
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
  4) List orders sent this session
  5) LOGOUT (disconnect the FIX session)
  6) LOGON  (reconnect the FIX session)
  0) Exit the application
=================================================================
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
| `TargetCompID` | KASE gateway's CompID. |
| `SocketConnectHost` | KASE FIX gateway hostname / IP. |
| `SocketConnectPort` | KASE FIX gateway TCP port. |
| `Username` | Your KASE FIX login (sent as tag 553). |
| `Password` | Your KASE FIX password (sent as tag 554). |
| `BeginString` | FIX version. Defaults to `FIX.4.4`. Change only if KASE says so. |
| `HeartBtInt` | Heartbeat interval; match KASE's requirement (often `30`). |
| `SenderSubID` / `TargetSubID` | Optional – uncomment only if KASE requires them. |
| `SocketUseSSL` | Uncomment if KASE requires a TLS/SSL connection. |

**If KASE requires a FIX version other than 4.4:** change `BeginString` in the
config **and** swap the message library in `pom.xml`
(`quickfixj-messages-fix44` → e.g. `quickfixj-messages-fix42`), then rebuild.

**If KASE provides a custom data dictionary** (a `*.xml` file describing their
exact message layout): drop it into `config/`, then in the config set
`DataDictionary=config/THEIR_FILE.xml`.

---

## 5. How the four functionalities work

### Logon
Handled automatically by the engine on startup. Our code attaches the
`Username`/`Password` to the outgoing Logon in
`KaseFixApplication.toAdmin(...)`. A successful logon prints a big
`LOGON SUCCESSFUL` banner. Use menu option `6` to log on again after a logout.

### Place New Order (option 2)
You are prompted for symbol, side (B/S), quantity and an optional limit price
(leave the price empty for a **market** order). The app builds a
`NewOrderSingle` in `OrderManager.sendNewOrder(...)`, sends it, and prints the
**ClOrdID** you'll need in order to cancel it later. The exchange's reply
arrives as an **ExecutionReport** and is explained in plain English in the log.

### Cancel Order (option 3)
Pick the ClOrdID of an order you placed during this run. The app builds an
`OrderCancelRequest` in `OrderManager.sendCancel(...)`. If the cancel is
rejected, the reason is logged clearly.

> Note: this simple client only remembers orders placed during the **current**
> run (in memory). That keeps the example easy to follow.

### Logout (option 5)
Sends a FIX `Logout` and disconnects. The app keeps running so you can log on
again (option 6) or exit (option 0).

---

## 6. Where to look when something goes wrong

- **Console** – live, colourless, human-readable trace of everything.
- **`logs/kase-fix-client.log`** – the same trace, saved to disk (rotated daily).
- **`logs/fix-messages/`** – the raw FIX messages exactly as sent/received
  (great for an audit trail or for sharing with KASE support).

In the logs, outgoing/incoming messages are tagged:

```
--> ADMIN OUT : ...   (we are sending a session message, e.g. Logon)
<-- ADMIN IN  : ...   (server sent us a session message)
--> APP OUT   : ...   (we are sending a business message, e.g. an order)
<-- APP IN    : ...   (server sent us a business message, e.g. ExecutionReport)
```

The raw FIX `0x01` field separator is shown as `|` to make it readable.

---

## 7. Quick troubleshooting

| Symptom | Likely cause / fix |
| --- | --- |
| Stuck at `NOT logged on`, connection retries | Host/port/credentials are still placeholders, or KASE gateway not reachable / not in trading hours. |
| `Server LOGOUT reason: ...` in the log | KASE rejected the logon – read the reason text (bad credentials, wrong CompID, etc.). |
| Orders rejected with a `Text` message | KASE didn't like a field (symbol, price tick, lot size...). Read the `Text` in the ExecutionReport. |
| `Missing ConnectionType` / config errors | Make sure you didn't break the `.cfg` format; comments start with `#`. |
```
