package kz.kase.fixclient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.DefaultMessageFactory;
import quickfix.FileLogFactory;
import quickfix.FileStoreFactory;
import quickfix.Initiator;
import quickfix.LogFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;

/**
 * KaseFixClient  -  APPLICATION ENTRY POINT
 * =========================================
 * This is the class you run. It does three things:
 *
 *   1. STARTS the FIX engine (which connects to KASE and logs on).
 *   2. Shows an interactive text MENU in your terminal.
 *   3. Lets you place orders, cancel them, log out, and exit.
 *
 * The four functionalities you asked for map to the menu like this:
 *   - LOGON       -> happens automatically on start (option 6 re-logs on)
 *   - NEW ORDER   -> menu option 2
 *   - CANCEL      -> menu option 3
 *   - LOGOUT      -> menu option 5
 *
 * HOW TO RUN:
 *   mvn -q clean package
 *   java -jar target/kase-fix-client.jar
 *
 * (See README.md for full instructions and what to substitute.)
 */
public class KaseFixClient {

    private static final Logger log = LoggerFactory.getLogger(KaseFixClient.class);

    /** Path to the QuickFIX/J configuration file (relative to where you run). */
    private static final String DEFAULT_CONFIG_PATH = "config/kase-fix-client.cfg";

    public static void main(String[] args) throws Exception {
        // Allow an optional custom config path: java -jar ... myconfig.cfg
        String configPath = (args.length > 0) ? args[0] : DEFAULT_CONFIG_PATH;

        log.info("Starting KASE FIX client using config: {}", configPath);

        // -----------------------------------------------------------------
        // 1) Load the session settings from the .cfg file.
        // -----------------------------------------------------------------
        SessionSettings settings = new SessionSettings(configPath);

        // -----------------------------------------------------------------
        // 2) Create our application (the event handler) and give it the
        //    username/password from the config so it can log us in.
        // -----------------------------------------------------------------
        KaseFixApplication application = new KaseFixApplication();
        SessionID configuredSessionId = injectCredentials(settings, application);

        // -----------------------------------------------------------------
        // 3) Wire up the standard QuickFIX/J building blocks:
        //    - storeFactory: remembers sequence numbers between runs
        //    - logFactory  : writes the raw FIX messages to logs/fix-messages
        //    - msgFactory  : knows how to build FIX message objects
        // -----------------------------------------------------------------
        MessageStoreFactory storeFactory = new FileStoreFactory(settings);
        LogFactory logFactory = new FileLogFactory(settings);
        MessageFactory msgFactory = new DefaultMessageFactory();

        // -----------------------------------------------------------------
        // 4) Create the INITIATOR. An "initiator" is the side that connects
        //    out to the server (us). It will automatically connect and send
        //    the Logon as soon as we call start().
        // -----------------------------------------------------------------
        Initiator initiator = new SocketInitiator(
                application, storeFactory, settings, logFactory, msgFactory);

        OrderManager orderManager = new OrderManager(application);

        // Make sure we shut down cleanly if the process is killed (Ctrl+C).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook: stopping FIX engine...");
            initiator.stop();
        }));

        // -----------------------------------------------------------------
        // 5) START -> this triggers the connection + LOGON to KASE.
        // -----------------------------------------------------------------
        initiator.start();
        log.info("FIX engine started. Attempting to connect & logon to KASE...");
        log.info("(If the host/port/credentials are still placeholders, logon will fail - that is expected.)");

        // -----------------------------------------------------------------
        // 6) Run the interactive menu until the user chooses to exit.
        // -----------------------------------------------------------------
        runMenu(application, orderManager, configuredSessionId);

        // -----------------------------------------------------------------
        // 7) Clean shutdown.
        // -----------------------------------------------------------------
        log.info("Stopping FIX engine...");
        initiator.stop();
        log.info("Goodbye.");
    }

    /**
     * Reads the Username/Password keys from the [SESSION] section of the .cfg
     * file and passes them to the application so they can be put into the
     * Logon message.
     */
    private static SessionID injectCredentials(SessionSettings settings, KaseFixApplication application)
            throws Exception {
        // A config file can technically hold several sessions; we use the first.
        SessionID sessionId = settings.sectionIterator().next();

        String username = settings.isSetting(sessionId, "Username")
                ? settings.getString(sessionId, "Username") : null;
        String password = settings.isSetting(sessionId, "Password")
                ? settings.getString(sessionId, "Password") : null;

        application.setCredentials(username, password);
        log.info("Loaded session '{}'. Logon username = '{}'.", sessionId, username);
        return sessionId;
    }

    // =====================================================================
    //  INTERACTIVE MENU
    // =====================================================================

    private static void runMenu(KaseFixApplication application, OrderManager orderManager,
                                SessionID configuredSessionId) {
        // Scanner reads what you type in the terminal.
        Scanner in = new Scanner(System.in);

        while (true) {
            printMenu(application);
            System.out.print("Choose an option: ");
            String choice = in.hasNextLine() ? in.nextLine().trim() : "0";

            try {
                switch (choice) {
                    case "1" -> showStatus(application, orderManager);
                    case "2" -> doNewOrder(in, orderManager, application);
                    case "3" -> doCancel(in, orderManager, application);
                    case "4" -> listOrders(orderManager);
                    case "5" -> doLogout(application);
                    case "6" -> doLogon(configuredSessionId);
                    case "0" -> {
                        log.info("Exit requested by user.");
                        return;
                    }
                    case "" -> { /* user just pressed Enter; redraw menu */ }
                    default -> System.out.println("Unknown option: '" + choice + "'");
                }
            } catch (Exception e) {
                // Never let a bad input crash the whole app.
                log.error("Something went wrong handling that option.", e);
            }
        }
    }

    private static void printMenu(KaseFixApplication application) {
        String status = application.isLoggedOn() ? "LOGGED ON" : "NOT logged on";
        System.out.println();
        System.out.println("============== KASE FIX CLIENT  [" + status + "] ==============");
        System.out.println("  1) Show connection status");
        System.out.println("  2) Place NEW order");
        System.out.println("  3) CANCEL an order");
        System.out.println("  4) List orders sent this session");
        System.out.println("  5) LOGOUT (disconnect the FIX session)");
        System.out.println("  6) LOGON  (reconnect the FIX session)");
        System.out.println("  0) Exit the application");
        System.out.println("=================================================================");
    }

    private static void showStatus(KaseFixApplication application, OrderManager orderManager) {
        System.out.println("Logged on : " + application.isLoggedOn());
        System.out.println("Session   : " + application.getActiveSessionId());
        System.out.println("Orders sent this session: " + orderManager.getSentOrders().size());
    }

    /** Menu option 2: collect order details and send a NewOrderSingle. */
    private static void doNewOrder(Scanner in, OrderManager orderManager, KaseFixApplication application) {
        if (!application.isLoggedOn()) {
            System.out.println(">> You are not logged on yet. Wait for LOGON or use option 6.");
            return;
        }

        System.out.print("Symbol (e.g. KZAP): ");
        String symbol = in.nextLine().trim();

        System.out.print("Side - type B for BUY or S for SELL: ");
        String sideInput = in.nextLine().trim().toUpperCase();
        boolean buy = !sideInput.startsWith("S");

        System.out.print("Quantity: ");
        BigDecimal qty = new BigDecimal(in.nextLine().trim());

        System.out.print("Limit price (leave EMPTY for a MARKET order): ");
        String priceInput = in.nextLine().trim();
        BigDecimal price = priceInput.isEmpty() ? null : new BigDecimal(priceInput);

        String clOrdId = orderManager.sendNewOrder(symbol, buy, qty, price);
        if (clOrdId != null) {
            System.out.println(">> Order sent. Remember this ClOrdID to cancel it later: " + clOrdId);
        }
    }

    /** Menu option 3: ask which order to cancel and send an OrderCancelRequest. */
    private static void doCancel(Scanner in, OrderManager orderManager, KaseFixApplication application) {
        if (!application.isLoggedOn()) {
            System.out.println(">> You are not logged on yet. Wait for LOGON or use option 6.");
            return;
        }

        Map<String, OrderManager.SentOrder> orders = orderManager.getSentOrders();
        if (orders.isEmpty()) {
            System.out.println(">> No orders to cancel (none sent during this run).");
            return;
        }

        listOrders(orderManager);
        System.out.print("Enter the ClOrdID of the order to cancel: ");
        String clOrdId = in.nextLine().trim();

        String cancelId = orderManager.sendCancel(clOrdId);
        if (cancelId != null) {
            System.out.println(">> Cancel request sent (cancel ClOrdID = " + cancelId + ").");
        }
    }

    /** Menu option 4: print every order we sent during this run. */
    private static void listOrders(OrderManager orderManager) {
        Map<String, OrderManager.SentOrder> orders = orderManager.getSentOrders();
        if (orders.isEmpty()) {
            System.out.println(">> No orders sent yet.");
            return;
        }
        System.out.println("---- Orders sent this session ----");
        orders.values().forEach(o -> System.out.printf(
                "  ClOrdID=%s | %s %s x %s @ %s%n",
                o.clOrdId(),
                o.side() == quickfix.field.Side.BUY ? "BUY" : "SELL",
                o.quantity(), o.symbol(),
                o.price() != null ? o.price() : "MARKET"));
        System.out.println("----------------------------------");
    }

    /** Menu option 5: politely log out of the FIX session. */
    private static void doLogout(KaseFixApplication application) {
        SessionID sessionId = application.getActiveSessionId();
        if (sessionId == null) {
            System.out.println(">> Already logged out / not connected.");
            return;
        }
        Session session = Session.lookupSession(sessionId);
        if (session != null) {
            log.info("Sending LOGOUT for session {}", sessionId);
            session.logout("User requested logout");
        }
    }

    /** Menu option 6: ask the engine to log on again after a logout. */
    private static void doLogon(SessionID configuredSessionId) {
        Session session = Session.lookupSession(configuredSessionId);
        if (session == null) {
            System.out.println(">> Could not find session " + configuredSessionId);
            return;
        }
        log.info("Requesting LOGON for session {}", configuredSessionId);
        session.logon();
        System.out.println(">> Logon requested. Watch the log for the LOGON confirmation.");
    }
}
