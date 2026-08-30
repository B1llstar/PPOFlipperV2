package net.runelite.client.plugins.microbot.gestarv2;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeSlots;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.grandexchange.models.GrandExchangeOfferDetails;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

import javax.inject.Inject;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * State machine that walks the buy/sell order lists, submits offers through
 * {@link Rs2GrandExchange}, and collects fills as they complete. Guardrails from
 * {@link GeStarGuardrails} are checked immediately before every offer submission.
 */
@Slf4j
public class GeStarV2Script extends Script {

    private static final int SCHEDULE_INTERVAL_MS = 600;

    enum State {
        GOING_TO_GE,
        PREPARING_FUNDS_OR_ITEMS,
        SUBMITTING_ORDERS,
        MONITORING_OFFERS,
        DONE
    }

    private GeStarV2Config config;
    private GeStarGuardrails guardrails;

    private State state = State.GOING_TO_GE;
    private final Deque<GeStarOrder> pendingOrders = new ArrayDeque<>();
    private final Map<GrandExchangeSlots, GeStarOrder> activeOrders = new LinkedHashMap<>();
    private GeStarOrder ordersAwaitingFunds;
    private GeStarOrder lastFundsShortfallOrder;

    public boolean run(GeStarV2Config config) {
        this.config = config;
        this.guardrails = new GeStarGuardrails(config);
        this.guardrails.reset();
        this.state = State.GOING_TO_GE;
        this.pendingOrders.clear();
        this.activeOrders.clear();
        this.ordersAwaitingFunds = null;
        this.lastFundsShortfallOrder = null;

        loadOrders();

        Rs2AntibanSettings.naturalMouse = true;
        Rs2Antiban.setActivityIntensity(ActivityIntensity.LOW);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!super.run()) return;
                if (!Microbot.isLoggedIn()) return;

                tick();
            } catch (Exception ex) {
                log.error("Error in GeStarV2Script: {} - ", ex.getMessage(), ex);
            }
        }, 0, SCHEDULE_INTERVAL_MS, TimeUnit.MILLISECONDS);

        return true;
    }

    @Override
    public void shutdown() {
        pendingOrders.clear();
        activeOrders.clear();
        ordersAwaitingFunds = null;
        lastFundsShortfallOrder = null;
        super.shutdown();
    }

    private void loadOrders() {
        Arrays.stream(config.buyOrders().split("\\r?\\n"))
            .map(line -> GeStarOrder.parse(GrandExchangeAction.BUY, line))
            .filter(java.util.Objects::nonNull)
            .forEach(pendingOrders::add);

        Arrays.stream(config.sellOrders().split("\\r?\\n"))
            .map(line -> GeStarOrder.parse(GrandExchangeAction.SELL, line))
            .filter(java.util.Objects::nonNull)
            .forEach(pendingOrders::add);

        log.info("GE Star V2 loaded {} order(s): {}", pendingOrders.size(),
            pendingOrders.stream().map(GeStarOrder::toString).collect(Collectors.joining(" | ")));
    }

    private void tick() {
        switch (state) {
            case GOING_TO_GE:
                if (Rs2GrandExchange.isOpen()) {
                    state = State.SUBMITTING_ORDERS;
                    return;
                }
                Rs2GrandExchange.walkToGrandExchange();
                if (Rs2GrandExchange.openExchange()) {
                    state = State.SUBMITTING_ORDERS;
                }
                break;

            case PREPARING_FUNDS_OR_ITEMS:
                prepareFundsOrItems();
                break;

            case SUBMITTING_ORDERS:
                submitNextOrder();
                break;

            case MONITORING_OFFERS:
                monitorOffers();
                break;

            case DONE:
                if (config.stopWhenOrdersComplete()) {
                    log.info("GE Star V2: all orders complete, stopping plugin.");
                    Microbot.getClientThread().invoke(() -> Microbot.stopPlugin(
                        Microbot.getPluginManager().getPlugins().stream()
                            .filter(p -> p.getClass().getSimpleName().equals("GeStarV2Plugin"))
                            .findFirst()
                            .orElse(null)));
                }
                break;
        }
    }

    private void prepareFundsOrItems() {
        if (ordersAwaitingFunds == null) {
            state = State.SUBMITTING_ORDERS;
            return;
        }

        if (!config.withdrawFromBank()) {
            log.warn("GE Star V2: insufficient funds/items for {} and bank withdrawal is disabled, skipping.", ordersAwaitingFunds);
            ordersAwaitingFunds = null;
            state = State.SUBMITTING_ORDERS;
            return;
        }

        if (!Rs2Bank.isOpen()) {
            if (!Rs2Bank.openBank()) return;
            sleepUntil(Rs2Bank::isOpen);
        }

        if (ordersAwaitingFunds.getAction() == GrandExchangeAction.BUY) {
            long needed = ordersAwaitingFunds.totalValue() - Rs2Inventory.count("Coins");
            if (needed > 0) {
                Rs2Bank.withdrawX("Coins", (int) needed);
                Rs2Inventory.waitForInventoryChanges(5000);
            }
        } else {
            int have = Rs2Inventory.count(ordersAwaitingFunds.getItemName());
            int needed = ordersAwaitingFunds.getQuantity() - have;
            if (needed > 0) {
                Rs2Bank.withdrawX(ordersAwaitingFunds.getItemName(), needed);
                Rs2Inventory.waitForInventoryChanges(5000);
            }
        }

        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen());
        ordersAwaitingFunds = null;
        state = State.SUBMITTING_ORDERS;
    }

    private void submitNextOrder() {
        if (!Rs2GrandExchange.isOpen()) {
            Rs2GrandExchange.openExchange();
            return;
        }

        if (activeOrders.size() >= Math.max(1, config.maxActiveOffers())) {
            state = State.MONITORING_OFFERS;
            return;
        }

        if (pendingOrders.isEmpty()) {
            state = activeOrders.isEmpty() ? State.DONE : State.MONITORING_OFFERS;
            return;
        }

        GeStarOrder order = pendingOrders.peekFirst();

        String rejection = guardrails.check(order);
        if (rejection != null) {
            log.warn("GE Star V2: rejected order [{}] - {}", order, rejection);
            pendingOrders.pollFirst();
            if (config.stopOnGuardrailBreach()) {
                log.error("GE Star V2: stopping on guardrail breach as configured.");
                Microbot.getClientThread().invoke(() -> Microbot.stopPlugin(
                    Microbot.getPluginManager().getPlugins().stream()
                        .filter(p -> p.getClass().getSimpleName().equals("GeStarV2Plugin"))
                        .findFirst()
                        .orElse(null)));
            }
            return;
        }

        if (!hasFundsOrItems(order)) {
            if (order == lastFundsShortfallOrder) {
                log.warn("GE Star V2: still short funds/items for {} after a bank visit, skipping.", order);
                pendingOrders.pollFirst();
                lastFundsShortfallOrder = null;
                return;
            }
            lastFundsShortfallOrder = order;
            ordersAwaitingFunds = order;
            state = State.PREPARING_FUNDS_OR_ITEMS;
            return;
        }
        lastFundsShortfallOrder = null;

        GrandExchangeSlots slotBefore = Rs2GrandExchange.getAvailableSlot();
        boolean submitted = order.getAction() == GrandExchangeAction.BUY
            ? Rs2GrandExchange.buyItem(order.getItemName(), order.getQuantity(), order.getPrice())
            : Rs2GrandExchange.sellItem(order.getItemName(), order.getQuantity(), order.getPrice());

        if (submitted) {
            pendingOrders.pollFirst();
            if (order.getAction() == GrandExchangeAction.BUY) {
                guardrails.recordSpend(order.totalValue());
            }
            GrandExchangeSlots slot = slotBefore != null ? slotBefore : Rs2GrandExchange.findSlotForItem(order.getItemName(), order.getAction() == GrandExchangeAction.BUY);
            if (slot != null) {
                activeOrders.put(slot, order);
            }
            log.info("GE Star V2: submitted {}", order);
        } else {
            log.warn("GE Star V2: failed to submit order {}, will retry next tick.", order);
        }
    }

    private boolean hasFundsOrItems(GeStarOrder order) {
        if (order.getAction() == GrandExchangeAction.BUY) {
            return Rs2Inventory.count("Coins") >= order.totalValue();
        }
        return Rs2Inventory.count(order.getItemName()) >= order.getQuantity();
    }

    private void monitorOffers() {
        if (!Rs2GrandExchange.isOpen()) {
            Rs2GrandExchange.openExchange();
            return;
        }

        // Auto-detect completed/partially-filled offers via the live offer details API
        // (backed by the same client state GrandExchangeOfferChanged notifies us of).
        for (Map.Entry<GrandExchangeSlots, GeStarOrder> entry : new LinkedHashMap<>(activeOrders).entrySet()) {
            GrandExchangeSlots slot = entry.getKey();
            GeStarOrder order = entry.getValue();

            GrandExchangeOfferDetails details = Rs2GrandExchange.getOfferDetails(slot);
            if (details == null) continue;

            int filled = order.getAction() == GrandExchangeAction.BUY
                ? Rs2GrandExchange.getItemsBoughtFromOffer(slot)
                : Rs2GrandExchange.getItemsSoldFromOffer(slot);

            GrandExchangeOfferState offerState = details.getState();
            boolean finished = offerState == GrandExchangeOfferState.BOUGHT
                || offerState == GrandExchangeOfferState.SOLD
                || offerState == GrandExchangeOfferState.CANCELLED_BUY
                || offerState == GrandExchangeOfferState.CANCELLED_SELL;

            if (finished) {
                log.info("GE Star V2: offer complete in slot {} - {} ({} filled)", slot, order, filled);
                Rs2GrandExchange.collectOffer(slot, config.collectToBank());
                activeOrders.remove(slot);
            }
        }

        if (activeOrders.isEmpty() && pendingOrders.isEmpty()) {
            state = State.DONE;
        } else if (activeOrders.size() < Math.max(1, config.maxActiveOffers()) && !pendingOrders.isEmpty()) {
            state = State.SUBMITTING_ORDERS;
        }
    }

    /**
     * Called from the plugin's {@code @Subscribe} handler on every GrandExchangeOfferChanged
     * event. The polling in {@link #monitorOffers()} already reads live state each tick, so
     * this is just a low-cost log line confirming fills are being observed in real time rather
     * than only when we happen to poll.
     */
    public void onOfferChanged(GrandExchangeOfferChanged event) {
        if (event.getOffer() == null) return;
        GrandExchangeOfferState offerState = event.getOffer().getState();
        if (offerState == GrandExchangeOfferState.BOUGHT || offerState == GrandExchangeOfferState.SOLD) {
            log.info("GE Star V2: detected fill in slot {} - {} of {}, state {}",
                event.getSlot(), event.getOffer().getQuantitySold(), event.getOffer().getTotalQuantity(), offerState);
        }
    }

    public int getPendingOrderCount() {
        return pendingOrders.size();
    }

    public int getActiveOfferCount() {
        return activeOrders.size();
    }

    public long getGpSpentThisSession() {
        return guardrails == null ? 0 : guardrails.getGpSpentThisSession();
    }

    public State getState() {
        return state;
    }
}
