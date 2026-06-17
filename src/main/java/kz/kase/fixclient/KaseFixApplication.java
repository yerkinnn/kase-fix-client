package kz.kase.fixclient;

import java.util.StringJoiner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.Application;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.RejectLogon;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.UnsupportedMessageType;
import quickfix.field.Account;
import quickfix.field.AvgPx;
import quickfix.field.BusinessRejectReason;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.CxlRejReason;
import quickfix.field.ExecType;
import quickfix.field.HeartBtInt;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.MsgSeqNum;
import quickfix.field.MsgType;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Password;
import quickfix.field.Price;
import quickfix.field.RefMsgType;
import quickfix.field.RefSeqNum;
import quickfix.field.RefTagID;
import quickfix.field.ResetSeqNumFlag;
import quickfix.field.SessionRejectReason;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TradSesStatus;
import quickfix.field.TradingSessionID;
import quickfix.field.Username;

/**
 * KaseFixApplication
 * ------------------
 * This class is the "heart" of the client. QuickFIX/J calls the methods in
 * this class automatically as things happen on the FIX connection.
 *
 * Think of it as a set of event handlers. You never call these methods
 * yourself - the FIX engine calls them for you:
 *
 *   onCreate   -> a session object was created (app starting up)
 *   onLogon    -> we successfully logged in to the server
 *   onLogout   -> we logged out / got disconnected
 *   toAdmin    -> the engine is about to SEND an admin message (Logon, Heartbeat...)
 *   fromAdmin  -> we RECEIVED an admin message from the server
 *   toApp      -> the engine is about to SEND a business message (our orders)
 *   fromApp    -> we RECEIVED a business message (e.g. ExecutionReport)
 *
 * Every method here logs what is happening so you can follow the whole
 * conversation in the console and in logs/kase-fix-client.log.
 */
public class KaseFixApplication implements Application {

    private static final Logger log = LoggerFactory.getLogger(KaseFixApplication.class);

    /**
     * The session id of the live connection. It is set when we log on and
     * cleared when we log out. Other parts of the program read this to know
     * whether we are currently connected, and where to send messages.
     *
     * Marked "volatile" because it is written by the FIX engine threads and
     * read by the main (menu) thread.
     */
    private volatile SessionID activeSessionId;

    /**
     * Reference to the OrderManager so that, when an ExecutionReport comes in,
     * we can store the exchange OrderID (37) against the order we sent. Set
     * once at startup via {@link #setOrderManager(OrderManager)}.
     */
    private volatile OrderManager orderManager;

    /** Wire in the OrderManager (called once at startup). */
    public void setOrderManager(OrderManager orderManager) {
        this.orderManager = orderManager;
    }

    /** @return the currently logged-on session, or null if not connected. */
    public SessionID getActiveSessionId() {
        return activeSessionId;
    }

    /** @return true if we are currently logged on to KASE. */
    public boolean isLoggedOn() {
        return activeSessionId != null;
    }

    // =====================================================================
    //  SESSION LIFECYCLE EVENTS
    // =====================================================================

    /**
     * Called once, very early, when QuickFIX/J creates the session object.
     * The connection is NOT established yet at this point.
     */
    @Override
    public void onCreate(SessionID sessionId) {
        log.info("Session created (not yet logged on): {}", sessionId);
    }

    /**
     * Called when we have SUCCESSFULLY logged on to the FIX server.
     * After this point we are allowed to send orders.
     */
    @Override
    public void onLogon(SessionID sessionId) {
        this.activeSessionId = sessionId;
        log.info("==========================================================");
        log.info(" LOGON SUCCESSFUL  ->  {}", sessionId);
        log.info(" You can now place and cancel orders.");
        log.info("==========================================================");
    }

    /**
     * Called when the session logs out OR the TCP connection drops.
     */
    @Override
    public void onLogout(SessionID sessionId) {
        this.activeSessionId = null;
        log.info("==========================================================");
        log.info(" LOGGED OUT / DISCONNECTED  ->  {}", sessionId);
        log.info("==========================================================");
    }

    // =====================================================================
    //  ADMIN (SESSION-LEVEL) MESSAGES: Logon, Logout, Heartbeat, etc.
    // =====================================================================

    /**
     * Called just before the engine SENDS an administrative message.
     *
     * This is where we attach our username and password to the outgoing
     * Logon message. KASE (like most venues) authenticates using:
     *   tag 553 = Username
     *   tag 554 = Password
     *
     * The actual Username/Password values come from the .cfg file and are
     * passed into this class via the constructor-less settings lookup in
     * KaseFixClient (see how credentials are injected below).
     */
    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);

            // MsgType "A" == Logon. Only then do we add credentials.
            if (MsgType.LOGON.equals(msgType)) {
                if (logonUsername != null && !logonUsername.isBlank()) {
                    message.setString(Username.FIELD, logonUsername);
                }
                if (logonPassword != null && !logonPassword.isBlank()) {
                    message.setString(Password.FIELD, logonPassword);
                }
                log.info("Sending LOGON (credentials attached for user '{}').", logonUsername);
            }
        } catch (FieldNotFound e) {
            log.warn("Could not read MsgType while preparing admin message.", e);
        }
        // Parsed, human-readable summary first, then the exact raw FIX line.
        log.debug("{}", describe(message, false));
        log.debug("--> ADMIN OUT: {}", prettyFix(message));
    }

    /**
     * Called when we RECEIVE an administrative message (Logon ack, Logout,
     * Heartbeat, Reject, etc.). We mostly just log these.
     */
    @Override
    public void fromAdmin(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
        // Parsed, human-readable summary first, then the exact raw FIX line.
        log.debug("{}", describe(message, true));
        log.debug("<-- ADMIN IN : {}", prettyFix(message));

        // If the server sends us a Logout with a reason (Text, tag 58),
        // surface it clearly - it usually explains WHY a logon was refused.
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            if (MsgType.LOGOUT.equals(msgType) && message.isSetField(Text.FIELD)) {
                log.warn("Server LOGOUT reason: {}", message.getString(Text.FIELD));
            }
        } catch (FieldNotFound ignored) {
            // No text field present - nothing to report.
        }
    }

    // =====================================================================
    //  APPLICATION (BUSINESS) MESSAGES: our orders + server responses
    // =====================================================================

    /**
     * Called just before the engine SENDS one of our business messages
     * (e.g. a NewOrderSingle or OrderCancelRequest). Good place to log it.
     */
    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend {
        // Parsed, human-readable summary first, then the exact raw FIX line.
        log.info("{}", describe(message, false));
        log.info("--> APP OUT  : {}", prettyFix(message));
    }

    /**
     * Called when we RECEIVE a business message from KASE. This is the most
     * important callback for trading: it is where ExecutionReports (order
     * accepted / filled / cancelled) and OrderCancelRejects arrive.
     */
    @Override
    public void fromApp(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        // Parsed, human-readable summary first, then the exact raw FIX line.
        log.info("{}", describe(message, true));
        log.info("<-- APP IN   : {}", prettyFix(message));

        String msgType = message.getHeader().getString(MsgType.FIELD);
        switch (msgType) {
            case MsgType.EXECUTION_REPORT -> handleExecutionReport(message);
            case MsgType.ORDER_CANCEL_REJECT -> handleCancelReject(message);
            default -> log.info("Received business message of type '{}' (no special handling).", msgType);
        }
    }

    /**
     * Nicely explain an ExecutionReport in plain language so it is easy to
     * understand what the exchange just told us about our order.
     */
    private void handleExecutionReport(Message m) throws FieldNotFound {
        String orderId   = m.isSetField(OrderID.FIELD)  ? m.getString(OrderID.FIELD)  : "(none)";
        char   ordStatus = m.isSetField(OrdStatus.FIELD) ? m.getChar(OrdStatus.FIELD)  : ' ';
        char   execType  = m.isSetField(ExecType.FIELD)  ? m.getChar(ExecType.FIELD)   : ' ';

        log.info("EXECUTION REPORT  | ExchangeOrderID={} | status={} | execType={}",
                orderId, describeOrdStatus(ordStatus), describeExecType(execType));

        // Link the exchange OrderID (37) back to our ClOrdID (11) so that a
        // later cancel can reference the exchange order number.
        if (orderManager != null && m.isSetField(ClOrdID.FIELD) && m.isSetField(OrderID.FIELD)) {
            orderManager.recordExchangeOrderId(m.getString(ClOrdID.FIELD), m.getString(OrderID.FIELD));
        }

        if (m.isSetField(LastPx.FIELD) && m.isSetField(LastQty.FIELD)) {
            log.info("   Last fill: qty={} @ price={}",
                    m.getString(LastQty.FIELD), m.getString(LastPx.FIELD));
        }
        if (m.isSetField(CumQty.FIELD) && m.isSetField(LeavesQty.FIELD)) {
            log.info("   Filled so far={}, remaining={}, avgPx={}",
                    m.getString(CumQty.FIELD),
                    m.getString(LeavesQty.FIELD),
                    m.isSetField(AvgPx.FIELD) ? m.getString(AvgPx.FIELD) : "n/a");
        }
        if (m.isSetField(Account.FIELD)) {
            log.info("   Account={}", m.getString(Account.FIELD));
        }
        if (m.isSetField(Text.FIELD)) {
            log.info("   Text from exchange: {}", m.getString(Text.FIELD));
        }
    }

    /** Explain why a cancel request was rejected. */
    private void handleCancelReject(Message m) throws FieldNotFound {
        log.warn("ORDER CANCEL REJECTED.");
        if (m.isSetField(CxlRejReason.FIELD)) {
            log.warn("   Reason code (tag 102) = {}", m.getInt(CxlRejReason.FIELD));
        }
        if (m.isSetField(Text.FIELD)) {
            log.warn("   Text from exchange: {}", m.getString(Text.FIELD));
        }
    }

    // =====================================================================
    //  HELPERS
    // =====================================================================

    /** Login credentials, injected from the .cfg file by KaseFixClient. */
    private String logonUsername;
    private String logonPassword;

    /** Called once at startup to give this app the credentials to send. */
    public void setCredentials(String username, String password) {
        this.logonUsername = username;
        this.logonPassword = password;
    }

    /**
     * Convenience method other classes can use to send a message on the
     * currently active session. Returns true if it was handed to the engine.
     */
    public boolean send(Message message) {
        if (activeSessionId == null) {
            log.warn("Cannot send message - not logged on yet.");
            return false;
        }
        try {
            return Session.sendToTarget(message, activeSessionId);
        } catch (SessionNotFound e) {
            log.error("Failed to send message - session not found.", e);
            return false;
        }
    }

    /** Turns the FIX wire format (SOH-delimited) into a readable string for logs. */
    private static String prettyFix(Message message) {
        // The raw FIX string uses an invisible 0x01 byte between fields.
        // Replace it with '|' so the log line is human readable.
        return message.toString().replace('\u0001', '|');
    }

    /**
     * Build a short, human-readable summary of a FIX message: the direction,
     * a friendly message-type name, and a few key fields for that type. This is
     * logged ALONGSIDE (just above) the raw FIX line so you can tell at a glance
     * what each IN/OUT message is. This method NEVER throws - logging must never
     * break message handling.
     *
     * Examples:
     *   "OUT -> LOGON | seq=1 | HeartBtInt=30 | ResetSeqNum=Y"
     *   "IN  <- HEARTBEAT | seq=18"
     *   "IN  <- EXECUTION REPORT | seq=4 | ClOrdID=ORD-1 | status=REJECTED | text=..."
     *
     * @param message  the FIX message
     * @param incoming true if we RECEIVED it, false if we are SENDING it
     */
    private static String describe(Message message, boolean incoming) {
        String dir = incoming ? "IN  <-" : "OUT ->";
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            StringBuilder sb = new StringBuilder(dir).append(' ').append(msgTypeName(msgType));

            // Sequence number (tag 34) lives in the header and is on every message.
            if (message.getHeader().isSetField(MsgSeqNum.FIELD)) {
                sb.append(" | seq=").append(message.getHeader().getString(MsgSeqNum.FIELD));
            }

            String key = keyFields(message, msgType);
            if (!key.isEmpty()) {
                sb.append(" | ").append(key);
            }
            return sb.toString();
        } catch (Exception e) {
            return dir + " (could not parse message: " + e.getMessage() + ")";
        }
    }

    /** Maps a FIX MsgType code (tag 35) to a friendly, readable name. */
    private static String msgTypeName(String msgType) {
        return switch (msgType) {
            case MsgType.HEARTBEAT -> "HEARTBEAT";
            case MsgType.TEST_REQUEST -> "TEST REQUEST";
            case MsgType.RESEND_REQUEST -> "RESEND REQUEST";
            case MsgType.REJECT -> "SESSION REJECT";
            case MsgType.SEQUENCE_RESET -> "SEQUENCE RESET";
            case MsgType.LOGOUT -> "LOGOUT";
            case MsgType.LOGON -> "LOGON";
            case MsgType.NEW_ORDER_SINGLE -> "NEW ORDER";
            case MsgType.ORDER_CANCEL_REQUEST -> "ORDER CANCEL REQUEST";
            case MsgType.ORDER_CANCEL_REPLACE_REQUEST -> "ORDER CANCEL/REPLACE";
            case MsgType.ORDER_MASS_CANCEL_REQUEST -> "ORDER MASS CANCEL REQUEST";
            case MsgType.EXECUTION_REPORT -> "EXECUTION REPORT";
            case MsgType.ORDER_CANCEL_REJECT -> "ORDER CANCEL REJECT";
            case MsgType.BUSINESS_MESSAGE_REJECT -> "BUSINESS MESSAGE REJECT";
            case MsgType.ORDER_MASS_CANCEL_REPORT -> "ORDER MASS CANCEL REPORT";
            case MsgType.TRADING_SESSION_STATUS -> "TRADING SESSION STATUS";
            case MsgType.TRADE_CAPTURE_REPORT -> "TRADE CAPTURE REPORT";
            default -> "MsgType=" + msgType;
        };
    }

    /**
     * Pick out a few important fields for the given message type and format
     * them as "label=value | label=value". Only present fields are included.
     */
    private static String keyFields(Message m, String msgType) throws FieldNotFound {
        StringJoiner j = new StringJoiner(" | ");
        switch (msgType) {
            case MsgType.LOGON -> {
                add(j, m, HeartBtInt.FIELD, "HeartBtInt");
                add(j, m, ResetSeqNumFlag.FIELD, "ResetSeqNum");
            }
            case MsgType.LOGOUT -> add(j, m, Text.FIELD, "text");
            case MsgType.REJECT -> {
                add(j, m, RefSeqNum.FIELD, "refSeq");
                add(j, m, RefTagID.FIELD, "refTag");
                add(j, m, SessionRejectReason.FIELD, "reason");
                add(j, m, Text.FIELD, "text");
            }
            case MsgType.NEW_ORDER_SINGLE -> {
                add(j, m, ClOrdID.FIELD, "ClOrdID");
                if (m.isSetField(Side.FIELD)) j.add(sideName(m.getChar(Side.FIELD)));
                add(j, m, OrderQty.FIELD, "qty");
                add(j, m, Symbol.FIELD, "symbol");
                add(j, m, Price.FIELD, "price");
                if (m.isSetField(OrdType.FIELD)) j.add(ordTypeName(m.getChar(OrdType.FIELD)));
            }
            case MsgType.ORDER_CANCEL_REQUEST -> {
                add(j, m, OrigClOrdID.FIELD, "OrigClOrdID");
                add(j, m, ClOrdID.FIELD, "ClOrdID");
                add(j, m, Symbol.FIELD, "symbol");
            }
            case MsgType.ORDER_CANCEL_REPLACE_REQUEST -> {
                add(j, m, OrigClOrdID.FIELD, "OrigClOrdID");
                add(j, m, ClOrdID.FIELD, "ClOrdID");
                if (m.isSetField(Side.FIELD)) j.add(sideName(m.getChar(Side.FIELD)));
                add(j, m, OrderQty.FIELD, "qty");
                add(j, m, Symbol.FIELD, "symbol");
                add(j, m, Price.FIELD, "price");
                if (m.isSetField(OrdType.FIELD)) j.add(ordTypeName(m.getChar(OrdType.FIELD)));
            }
            case MsgType.EXECUTION_REPORT -> {
                add(j, m, ClOrdID.FIELD, "ClOrdID");
                if (m.isSetField(OrdStatus.FIELD)) j.add("status=" + describeOrdStatus(m.getChar(OrdStatus.FIELD)));
                if (m.isSetField(ExecType.FIELD)) j.add("execType=" + describeExecType(m.getChar(ExecType.FIELD)));
                add(j, m, Text.FIELD, "text");
            }
            case MsgType.BUSINESS_MESSAGE_REJECT -> {
                add(j, m, RefMsgType.FIELD, "refMsgType");
                add(j, m, BusinessRejectReason.FIELD, "reason");
                add(j, m, Text.FIELD, "text");
            }
            case MsgType.ORDER_CANCEL_REJECT -> {
                add(j, m, CxlRejReason.FIELD, "reason");
                add(j, m, Text.FIELD, "text");
            }
            case MsgType.TRADING_SESSION_STATUS -> {
                add(j, m, TradingSessionID.FIELD, "board");
                add(j, m, TradSesStatus.FIELD, "status");
            }
            default -> {
                // HEARTBEAT, TEST REQUEST, SEQUENCE RESET, etc.: seq alone is enough.
            }
        }
        return j.toString();
    }

    /** Append "label=value" to the joiner, but only if the field is present. */
    private static void add(StringJoiner j, Message m, int tag, String label) throws FieldNotFound {
        if (m.isSetField(tag)) {
            j.add(label + "=" + m.getString(tag));
        }
    }

    private static String sideName(char s) {
        return switch (s) {
            case Side.BUY -> "BUY";
            case Side.SELL -> "SELL";
            default -> "side='" + s + "'";
        };
    }

    private static String ordTypeName(char t) {
        return switch (t) {
            case OrdType.MARKET -> "MARKET";
            case OrdType.LIMIT -> "LIMIT";
            default -> "ordType='" + t + "'";
        };
    }

    private static String describeOrdStatus(char s) {
        return switch (s) {
            case OrdStatus.NEW -> "NEW (accepted)";
            case OrdStatus.PARTIALLY_FILLED -> "PARTIALLY FILLED";
            case OrdStatus.FILLED -> "FILLED";
            case OrdStatus.CANCELED -> "CANCELLED";
            case OrdStatus.REJECTED -> "REJECTED";
            case OrdStatus.REPLACED -> "REPLACED";
            case OrdStatus.PENDING_NEW -> "PENDING NEW";
            case OrdStatus.PENDING_CANCEL -> "PENDING CANCEL";
            case OrdStatus.PENDING_REPLACE -> "PENDING REPLACE";
            default -> "status '" + s + "'";
        };
    }

    private static String describeExecType(char s) {
        return switch (s) {
            case ExecType.NEW -> "NEW";
            case ExecType.TRADE -> "TRADE (fill)";
            case ExecType.CANCELED -> "CANCELLED";
            case ExecType.REPLACED -> "REPLACED";
            case ExecType.REJECTED -> "REJECTED";
            case ExecType.PENDING_NEW -> "PENDING NEW";
            case ExecType.PENDING_CANCEL -> "PENDING CANCEL";
            case ExecType.PENDING_REPLACE -> "PENDING REPLACE";
            default -> "execType '" + s + "'";
        };
    }
}
