package kz.kase.fixclient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.field.Account;
import quickfix.field.ClOrdID;
import quickfix.field.MassCancelRequestType;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.PartyID;
import quickfix.field.PartyIDSource;
import quickfix.field.PartyRole;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TradingSessionID;
import quickfix.field.TransactTime;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelReplaceRequest;
import quickfix.fix44.OrderCancelRequest;
import quickfix.fix44.OrderMassCancelRequest;

/**
 * OrderManager
 * ------------
 * This class knows how to BUILD and SEND the two business messages we care
 * about:
 *
 *   1. NewOrderSingle              (FIX MsgType "D") -> place a brand new order
 *   2. OrderCancelRequest          (FIX MsgType "F") -> cancel an order we placed
 *   3. OrderCancelReplaceRequest   (FIX MsgType "G") -> modify price/qty
 *   4. OrderMassCancelRequest      (FIX MsgType "q") -> cancel many orders at once
 *
 * ===========================================================================
 *  IMPORTANT - KASE / MOEX "MFIX" SPECIFICS (see spec section 4.2.2):
 *  A New Order - Single (D) is ONLY accepted by KASE if it contains, at least:
 *
 *    11  ClOrdID            - our unique order id
 *    1   Account            - the trading account the order is booked to
 *    386 NoTradingSessions  - repeating group count, MUST be exactly 1
 *    336 TradingSessionID   - the BOARD / trading mode (a.k.a. SECBOARD)
 *    55  Symbol             - the instrument code (a.k.a. SECCODE)
 *    54  Side               - 1 = buy, 2 = sell
 *    60  TransactTime       - message time, in UTC
 *    38  OrderQty           - quantity in lots
 *    40  OrdType            - 1 = market, 2 = limit
 *    44  Price              - required for limit; MUST be 0 for market
 *
 *  The pair (336 TradingSessionID + 55 Symbol) must point at a real
 *  instrument, otherwise KASE rejects the order. If any of the mandatory
 *  fields above are missing, KASE replies with a Business Message Reject
 *  (35=j) and the generic text "Unsupported message type." - which is what
 *  happened before these fields were added.
 * ===========================================================================
 *
 * NOTE ON IDENTIFIERS:
 *   - ClOrdID (tag 11): a UNIQUE id WE generate for every order/cancel.
 *   - OrigClOrdID (tag 41): when cancelling, the ClOrdID of the ORIGINAL order.
 *   - OrderID (tag 37): the EXCHANGE's own order number, returned in the
 *     ExecutionReport. KASE recommends cancelling by OrderID when you have it.
 */
public class OrderManager {

    private static final Logger log = LoggerFactory.getLogger(OrderManager.class);

    private final KaseFixApplication application;

    /** Used to generate unique, increasing ClOrdIDs. */
    private final AtomicLong clOrdIdCounter = new AtomicLong(System.currentTimeMillis());

    /** Remembers the orders we have sent so we can cancel them later. */
    private final Map<String, SentOrder> sentOrders = new ConcurrentHashMap<>();

    public OrderManager(KaseFixApplication application) {
        this.application = application;
    }

    /**
     * A small, mutable holder for an order we sent, kept so we can cancel it.
     *
     * <p>{@code exchangeOrderId} starts out null and is filled in later, when
     * KASE sends back an ExecutionReport carrying the exchange OrderID (37).
     */
    public static final class SentOrder {
        final String clOrdId;
        final String symbol;
        final String board;     // TradingSessionID (336)
        final String account;   // Account (1)
        final char side;        // Side (54)
        final BigDecimal quantity;
        final BigDecimal price; // null = market order
        volatile String exchangeOrderId; // OrderID (37), learned from ExecutionReport

        SentOrder(String clOrdId, String symbol, String board, String account,
                  char side, BigDecimal quantity, BigDecimal price) {
            this.clOrdId = clOrdId;
            this.symbol = symbol;
            this.board = board;
            this.account = account;
            this.side = side;
            this.quantity = quantity;
            this.price = price;
        }

        public String clOrdId()         { return clOrdId; }
        public String symbol()          { return symbol; }
        public String board()           { return board; }
        public String account()         { return account; }
        public char side()              { return side; }
        public BigDecimal quantity()    { return quantity; }
        public BigDecimal price()       { return price; }
        public String exchangeOrderId() { return exchangeOrderId; }
    }

    /** @return an unmodifiable view of all orders we have sent this session. */
    public Map<String, SentOrder> getSentOrders() {
        return Map.copyOf(sentOrders);
    }

    /**
     * Record the exchange OrderID (tag 37) that KASE assigned to one of our
     * orders. Called from the FIX application when an ExecutionReport arrives.
     *
     * @param clOrdId         our client order id (tag 11) echoed back by KASE
     * @param exchangeOrderId the exchange's order number (tag 37)
     */
    public void recordExchangeOrderId(String clOrdId, String exchangeOrderId) {
        SentOrder order = sentOrders.get(clOrdId);
        if (order != null && exchangeOrderId != null && !exchangeOrderId.isBlank()) {
            order.exchangeOrderId = exchangeOrderId;
            log.debug("Linked our ClOrdID={} to exchange OrderID={}", clOrdId, exchangeOrderId);
        }
    }

    /**
     * Build and send a NEW ORDER (New Order - Single, MsgType "D").
     *
     * @param symbol     instrument code / SECCODE, e.g. "AIRA".
     * @param board      trading board / TradingSessionID / SECBOARD, e.g. "TQBR".
     * @param account    trading account the order is booked to (tag 1).
     * @param buy        true = BUY, false = SELL.
     * @param quantity   quantity in lots.
     * @param limitPrice limit price for a LIMIT order; pass null for a MARKET order.
     * @return the ClOrdID assigned to this order (use it to cancel later), or
     *         null if the message could not be sent.
     */
    public String sendNewOrder(String symbol, String board, String account,
                               boolean buy, BigDecimal quantity, BigDecimal limitPrice) {
        // 1) Generate a unique client order id.
        String clOrdId = nextClOrdId();

        // 2) Decide BUY vs SELL.
        char side = buy ? Side.BUY : Side.SELL;

        // 3) Decide LIMIT vs MARKET order.
        char ordType = (limitPrice != null) ? OrdType.LIMIT : OrdType.MARKET;

        // 4) Build the message. The FIX 4.4 NewOrderSingle constructor needs
        //    the four mandatory fields up front. NOTE: TransactTime must be UTC.
        NewOrderSingle order = new NewOrderSingle(
                new ClOrdID(clOrdId),
                new Side(side),
                new TransactTime(LocalDateTime.now(ZoneOffset.UTC)),
                new OrdType(ordType));

        // 5) Account (tag 1) - which trading account this order belongs to.
        order.set(new Account(account));

        // 6) TradingSessionID group (tags 386 + 336). KASE REQUIRES exactly one
        //    trading session, whose id is the board / trading mode (SECBOARD).
        //    Calling addGroup() makes QuickFIX/J set NoTradingSessions (386)=1
        //    automatically, and keeps 386 and 336 adjacent as KASE requires.
        NewOrderSingle.NoTradingSessions tradingSession = new NewOrderSingle.NoTradingSessions();
        tradingSession.set(new TradingSessionID(board));
        order.addGroup(tradingSession);

        // 7) Instrument + quantity.
        order.set(new Symbol(symbol));
        order.set(new OrderQty(quantity.doubleValue()));

        // 8) Price. Limit orders carry the real price; MARKET orders MUST send 0
        //    (this is a KASE/MOEX rule, see spec note on tag 44).
        if (limitPrice != null) {
            order.set(new Price(limitPrice.doubleValue()));
        } else {
            order.set(new Price(0));
        }

        log.info("Placing NEW {} order: {} x {} on board '{}', account '{}', @ {} (ClOrdID={})",
                buy ? "BUY" : "SELL", quantity, symbol, board, account,
                limitPrice != null ? limitPrice : "MARKET", clOrdId);

        // 9) Send it. If accepted by the engine, remember it for later cancels.
        boolean sent = application.send(order);
        if (sent) {
            sentOrders.put(clOrdId, new SentOrder(clOrdId, symbol, board, account, side, quantity, limitPrice));
            return clOrdId;
        }
        log.error("New order was NOT sent (are we logged on?).");
        return null;
    }

    /**
     * Build and send an ORDER CANCEL REQUEST (MsgType "F") for an order we
     * previously sent.
     *
     * <p>Per the KASE spec, a cancel needs the original order reference plus
     * Side and TransactTime. We reference the order by OrigClOrdID (41) and,
     * if KASE has already given us the exchange OrderID (37), we include that
     * too because KASE treats OrderID as the more reliable reference.
     *
     * @param origClOrdId the ClOrdID returned by {@link #sendNewOrder}.
     * @return the new ClOrdID assigned to the cancel request, or null on failure.
     */
    public String sendCancel(String origClOrdId) {
        SentOrder original = sentOrders.get(origClOrdId);
        if (original == null) {
            log.warn("Cannot cancel: no order found with ClOrdID '{}'. " +
                    "(This client only remembers orders sent during THIS run.)", origClOrdId);
            return null;
        }

        // The cancel request needs its OWN new unique ClOrdID.
        String cancelClOrdId = nextClOrdId();

        // FIX 4.4 OrderCancelRequest mandatory fields (TransactTime in UTC):
        OrderCancelRequest cancel = new OrderCancelRequest(
                new OrigClOrdID(original.clOrdId()), // which order to cancel
                new ClOrdID(cancelClOrdId),          // id of this cancel message
                new Side(original.side()),
                new TransactTime(LocalDateTime.now(ZoneOffset.UTC)));

        // If KASE has told us the exchange order number, reference it too -
        // KASE ignores OrigClOrdID when OrderID is present and treats it as
        // the authoritative reference.
        if (original.exchangeOrderId() != null && !original.exchangeOrderId().isBlank()) {
            cancel.set(new quickfix.field.OrderID(original.exchangeOrderId()));
        }

        // Repeat the instrument (required by the standard FIX 4.4 dictionary).
        cancel.set(new Symbol(original.symbol()));
        cancel.set(new OrderQty(original.quantity().doubleValue()));

        log.info("Cancelling order ClOrdID={} (exchangeOrderID={}, symbol={}, qty={}). Cancel ClOrdID={}",
                origClOrdId, original.exchangeOrderId(), original.symbol(), original.quantity(), cancelClOrdId);

        boolean sent = application.send(cancel);
        if (sent) {
            return cancelClOrdId;
        }
        log.error("Cancel request was NOT sent (are we logged on?).");
        return null;
    }

    /**
     * Build and send an ORDER CANCEL/REPLACE REQUEST (MsgType "G") to MODIFY an
     * order we previously sent. This is how you change the PRICE and/or QUANTITY
     * of a live order without first cancelling it yourself - KASE atomically
     * cancels the old order and replaces it with the amended one.
     *
     * <p>Per KASE spec section 4.2.4, a Cancel/Replace mirrors the New Order (D)
     * message (same mandatory fields: Account, the TradingSessionID group,
     * Symbol, Side, TransactTime, OrdType, OrderQty, Price) and additionally
     * references the original order via OrigClOrdID (41). As with a plain cancel,
     * we also include the exchange OrderID (37) when KASE has given it to us,
     * because KASE treats OrderID as the authoritative reference.
     *
     * <p>Anything passed as {@code null} keeps the ORIGINAL order's value, so the
     * caller can change just the price, just the quantity, or both.
     *
     * @param origClOrdId the ClOrdID of the order to modify (from a prior send).
     * @param newQuantity the new quantity in lots, or null to keep the original.
     * @param newPrice    the new limit price, or null to keep the original
     *                    (if the original was a MARKET order it stays MARKET).
     * @return the new ClOrdID assigned to the replace request, or null on failure.
     */
    public String sendCancelReplace(String origClOrdId, BigDecimal newQuantity, BigDecimal newPrice) {
        SentOrder original = sentOrders.get(origClOrdId);
        if (original == null) {
            log.warn("Cannot modify: no order found with ClOrdID '{}'. " +
                    "(This client only remembers orders sent during THIS run.)", origClOrdId);
            return null;
        }

        // Fall back to the original values for anything the caller left blank.
        BigDecimal quantity = (newQuantity != null) ? newQuantity : original.quantity();
        BigDecimal price    = (newPrice != null) ? newPrice : original.price();

        // OrdType follows the price: a price means LIMIT, no price means MARKET.
        char ordType = (price != null) ? OrdType.LIMIT : OrdType.MARKET;

        // The replace request needs its OWN new unique ClOrdID.
        String replaceClOrdId = nextClOrdId();

        // Build the message with explicit setters (clearer than relying on the
        // generated constructor's field order, and easy to map to the spec).
        OrderCancelReplaceRequest replace = new OrderCancelReplaceRequest();
        replace.set(new OrigClOrdID(original.clOrdId())); // which order to amend
        replace.set(new ClOrdID(replaceClOrdId));         // id of THIS replace
        replace.set(new Side(original.side()));           // side cannot change
        replace.set(new TransactTime(LocalDateTime.now(ZoneOffset.UTC)));
        replace.set(new OrdType(ordType));

        // Account (tag 1) - same trading account as the original.
        replace.set(new Account(original.account()));

        // TradingSessionID group (386 + 336) - same board as the original.
        OrderCancelReplaceRequest.NoTradingSessions tradingSession =
                new OrderCancelReplaceRequest.NoTradingSessions();
        tradingSession.set(new TradingSessionID(original.board()));
        replace.addGroup(tradingSession);

        // Instrument + the NEW quantity.
        replace.set(new Symbol(original.symbol()));
        replace.set(new OrderQty(quantity.doubleValue()));

        // Price: LIMIT carries the real (possibly new) price; MARKET MUST send 0.
        replace.set(new Price(price != null ? price.doubleValue() : 0));

        // Reference the exchange order number when we know it.
        if (original.exchangeOrderId() != null && !original.exchangeOrderId().isBlank()) {
            replace.set(new quickfix.field.OrderID(original.exchangeOrderId()));
        }

        log.info("Modifying order ClOrdID={} (exchangeOrderID={}, symbol={}): qty {} -> {}, price {} -> {}. Replace ClOrdID={}",
                origClOrdId, original.exchangeOrderId(), original.symbol(),
                original.quantity(), quantity,
                original.price() != null ? original.price() : "MARKET",
                price != null ? price : "MARKET",
                replaceClOrdId);

        boolean sent = application.send(replace);
        if (sent) {
            // Track the amended order under its new ClOrdID so it can itself be
            // cancelled or modified again later.
            sentOrders.put(replaceClOrdId, new SentOrder(
                    replaceClOrdId, original.symbol(), original.board(),
                    original.account(), original.side(), quantity, price));
            return replaceClOrdId;
        }
        log.error("Cancel/Replace request was NOT sent (are we logged on?).");
        return null;
    }

    // =====================================================================
    //  ORDER MASS CANCEL REQUEST (MsgType "q") - KASE spec section 4.2.5
    // =====================================================================
    //
    // ClOrdID(11) and TransactTime(60) are ALWAYS mandatory.
    // The scope is defined by MassCancelRequestType(530) plus a few
    // discriminator fields. KASE uses ONLY these combinations:
    //
    //   530='1' + TradingSessionID(336) + Symbol(55)  -> by instrument
    //   530='7' + Side(54)='1' or '2'                 -> buy / sell only
    //   530='7' + Account(1)                          -> by trading account
    //   530='7' + NoPartyIDs group                    -> by user or firm
    //   530='7' (nothing else)                        -> ALL orders
    //
    // Each public method below maps 1:1 to one KASE bullet so the caller
    // cannot accidentally mix the wrong fields.

    /** Cancel ALL orders: MassCancelRequestType(530) = '7' (no other fields). */
    public String sendMassCancelAll() {
        String clOrdId = nextClOrdId();
        OrderMassCancelRequest req = newMassCancel(clOrdId, MassCancelRequestType.CANCEL_ALL_ORDERS);
        return dispatch(req, clOrdId, "MASS CANCEL: ALL orders");
    }

    /**
     * Cancel orders for a specific instrument: 530='1' + board + symbol.
     * KASE requires BOTH TradingSessionID(336) and Symbol(55) together.
     */
    public String sendMassCancelBySecurity(String board, String symbol) {
        String clOrdId = nextClOrdId();
        OrderMassCancelRequest req = newMassCancel(clOrdId, MassCancelRequestType.CANCEL_ORDERS_FOR_A_SECURITY);
        req.set(new TradingSessionID(board));
        req.set(new Symbol(symbol));
        return dispatch(req, clOrdId,
                "MASS CANCEL: by SECURITY board='" + board + "' symbol='" + symbol + "'");
    }

    /** Cancel BUY or SELL orders only: 530='7' + Side(54) = '1' or '2'. */
    public String sendMassCancelBySide(boolean buy) {
        String clOrdId = nextClOrdId();
        OrderMassCancelRequest req = newMassCancel(clOrdId, MassCancelRequestType.CANCEL_ALL_ORDERS);
        req.set(new Side(buy ? Side.BUY : Side.SELL));
        return dispatch(req, clOrdId,
                "MASS CANCEL: " + (buy ? "BUY" : "SELL") + " orders only");
    }

    /** Cancel orders for a specific trading account: 530='7' + Account(1). */
    public String sendMassCancelByAccount(String account) {
        String clOrdId = nextClOrdId();
        OrderMassCancelRequest req = newMassCancel(clOrdId, MassCancelRequestType.CANCEL_ALL_ORDERS);
        // The generated FIX44 class does not expose a typed set(Account) here,
        // so we set tag 1 generically.
        req.setString(Account.FIELD, account);
        return dispatch(req, clOrdId,
                "MASS CANCEL: by ACCOUNT '" + account + "'");
    }

    /**
     * Cancel orders of a specific user or firm: 530='7' + NoPartyIDs(453)=1 group.
     * KASE requires PartyIDSource(447)='D' and PartyRole(452)='12' (user) or '1' (firm).
     *
     * @param partyId the user or firm identifier (PartyID, tag 448)
     * @param firm    true = firm (role 1), false = user (role 12)
     */
    public String sendMassCancelByParty(String partyId, boolean firm) {
        String clOrdId = nextClOrdId();
        OrderMassCancelRequest req = newMassCancel(clOrdId, MassCancelRequestType.CANCEL_ALL_ORDERS);
        // This FIX44 generated class may not expose NoPartyIDs as a typed inner
        // class, so we add the party group generically by tag sequence.
        quickfix.Group party = new quickfix.Group(453, PartyID.FIELD,
                new int[] { PartyID.FIELD, PartyIDSource.FIELD, PartyRole.FIELD, 0 });
        party.setString(PartyID.FIELD, partyId);
        party.setChar(PartyIDSource.FIELD, 'D');
        party.setInt(PartyRole.FIELD, firm ? PartyRole.EXECUTING_FIRM : PartyRole.EXECUTING_TRADER);
        req.addGroup(party);

        return dispatch(req, clOrdId,
                "MASS CANCEL: by " + (firm ? "FIRM" : "USER") + " id='" + partyId + "'");
    }

    /**
     * Build the common base of every Order Mass Cancel Request using the provided
     * ClOrdID, the given MassCancelRequestType, and UTC TransactTime
     * (all mandatory for KASE).
     */
    private OrderMassCancelRequest newMassCancel(String clOrdId, char requestType) {
        return new OrderMassCancelRequest(
                new ClOrdID(clOrdId),
                new MassCancelRequestType(requestType),
                new TransactTime(LocalDateTime.now(ZoneOffset.UTC)));
    }

    /**
     * Log, send, and return the ClOrdID. Mass cancel is fire-and-forget: we do
     * NOT add anything to sentOrders (it cancels existing orders; the report
     * and any ExecutionReports tell us the outcome).
     */
    private String dispatch(OrderMassCancelRequest req, String clOrdId, String description) {
        log.info("{} (ClOrdID={})", description, clOrdId);
        if (application.send(req)) {
            return clOrdId;
        }
        log.error("Mass cancel request was NOT sent (are we logged on?).");
        return null;
    }

    /** Generates a unique, strictly increasing client order id. */
    private String nextClOrdId() {
        return "ORD-" + clOrdIdCounter.incrementAndGet();
    }
}
