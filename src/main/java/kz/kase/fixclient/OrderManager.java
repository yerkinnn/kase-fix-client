package kz.kase.fixclient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.field.ClOrdID;
import quickfix.field.HandlInst;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelRequest;

/**
 * OrderManager
 * ------------
 * This class knows how to BUILD and SEND the two business messages we care
 * about:
 *
 *   1. NewOrderSingle     (FIX MsgType "D") -> place a brand new order
 *   2. OrderCancelRequest (FIX MsgType "F") -> cancel an order we placed
 *
 * It also keeps a small in-memory "order book" of the orders we have sent,
 * indexed by their ClOrdID. We need this because to cancel an order, the
 * exchange requires us to repeat some of the original order's details
 * (symbol, side, quantity) plus reference the original ClOrdID.
 *
 * NOTE ON IDENTIFIERS:
 *   - ClOrdID (tag 11): a UNIQUE id WE generate for every order/cancel.
 *     The exchange uses it to know which message we mean.
 *   - OrigClOrdID (tag 41): when cancelling, this is the ClOrdID of the
 *     ORIGINAL order we want to cancel.
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
     * A tiny record of an order we sent, kept so we can build a cancel for it.
     *
     * @param clOrdId the unique id we assigned to the order
     * @param symbol  the instrument (e.g. "KZAP")
     * @param side    Side.BUY ('1') or Side.SELL ('2')
     * @param quantity number of units ordered
     * @param price   limit price (may be null for a market order)
     */
    public record SentOrder(String clOrdId, String symbol, char side,
                            BigDecimal quantity, BigDecimal price) {
    }

    /** @return an unmodifiable view of all orders we have sent this session. */
    public Map<String, SentOrder> getSentOrders() {
        return Map.copyOf(sentOrders);
    }

    /**
     * Build and send a NEW ORDER.
     *
     * @param symbol     instrument symbol, e.g. "KZAP". SUBSTITUTE with a real
     *                   KASE symbol when testing.
     * @param buy        true = BUY, false = SELL
     * @param quantity   how many units to trade
     * @param limitPrice the price for a LIMIT order. Pass null to send a
     *                   MARKET order instead (no price).
     * @return the ClOrdID assigned to this order (you use it to cancel later),
     *         or null if the message could not be sent.
     */
    public String sendNewOrder(String symbol, boolean buy, BigDecimal quantity, BigDecimal limitPrice) {
        // 1) Generate a unique client order id.
        String clOrdId = nextClOrdId();

        // 2) Decide BUY vs SELL.
        char side = buy ? Side.BUY : Side.SELL;

        // 3) Decide LIMIT vs MARKET order.
        char ordType = (limitPrice != null) ? OrdType.LIMIT : OrdType.MARKET;

        // 4) Build the message. The FIX 4.4 NewOrderSingle constructor needs
        //    the four mandatory fields up front.
        NewOrderSingle order = new NewOrderSingle(
                new ClOrdID(clOrdId),
                new Side(side),
                new TransactTime(LocalDateTime.now()),
                new OrdType(ordType));

        // 5) Add the rest of the required/optional fields.
        order.set(new Symbol(symbol));
        order.set(new OrderQty(quantity.doubleValue()));

        // HandlInst (tag 21) = "1" means automated execution, no broker
        // intervention. Many venues require it; KASE may ignore it. Safe to send.
        order.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_NO_INTERVENTION));

        // Only LIMIT orders carry a price.
        if (limitPrice != null) {
            order.set(new Price(limitPrice.doubleValue()));
        }

        log.info("Placing NEW {} order: {} x {} @ {} (ClOrdID={})",
                buy ? "BUY" : "SELL", quantity, symbol,
                limitPrice != null ? limitPrice : "MARKET", clOrdId);

        // 6) Send it. If accepted by the engine, remember it for later cancels.
        boolean sent = application.send(order);
        if (sent) {
            sentOrders.put(clOrdId, new SentOrder(clOrdId, symbol, side, quantity, limitPrice));
            return clOrdId;
        }
        log.error("New order was NOT sent (are we logged on?).");
        return null;
    }

    /**
     * Build and send an ORDER CANCEL REQUEST for an order we previously sent.
     *
     * @param origClOrdId the ClOrdID returned by {@link #sendNewOrder} for the
     *                    order you now want to cancel.
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

        // FIX 4.4 OrderCancelRequest mandatory fields:
        OrderCancelRequest cancel = new OrderCancelRequest(
                new OrigClOrdID(original.clOrdId()), // which order to cancel
                new ClOrdID(cancelClOrdId),          // id of this cancel message
                new Side(original.side()),
                new TransactTime(LocalDateTime.now()));

        // Repeat the instrument and quantity from the original order.
        cancel.set(new Symbol(original.symbol()));
        cancel.set(new OrderQty(original.quantity().doubleValue()));

        log.info("Cancelling order ClOrdID={} (symbol={}, qty={}). Cancel ClOrdID={}",
                origClOrdId, original.symbol(), original.quantity(), cancelClOrdId);

        boolean sent = application.send(cancel);
        if (sent) {
            return cancelClOrdId;
        }
        log.error("Cancel request was NOT sent (are we logged on?).");
        return null;
    }

    /** Generates a unique, strictly increasing client order id. */
    private String nextClOrdId() {
        return "ORD-" + clOrdIdCounter.incrementAndGet();
    }
}
