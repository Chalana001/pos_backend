package com.chala.posapp.module;

import java.util.List;
import java.util.Map;

/**
 * The sales copy a shop sees when it opens something its package does not include.
 *
 * <p>Separate from {@link ModuleDefinition#description()}, which is written for the operator
 * running the control panel — one flat line saying what the module is. This is written for a
 * shop owner deciding whether to pay for it, so it leads with the problem the module solves
 * and names outcomes in their own terms: stock, cash, staff, customers.
 *
 * <p>Kept in the backend rather than the POS bundle so the copy can be corrected without a
 * frontend release, and so the control panel can show operators exactly what their customers
 * are being told.
 *
 * @param headline the hook — one line, the reason to care, never a restatement of the name
 * @param pitch    two sentences on what changes for the shop once it is on
 * @param outcomes concrete wins; deliberately specific, because "improves efficiency" sells
 *                 nothing to somebody counting a till at 10pm
 */
public record ModulePitch(String headline, String pitch, List<String> outcomes) {

    private static final Map<String, ModulePitch> PITCHES = Map.ofEntries(

            Map.entry("DASHBOARD", new ModulePitch(
                    "Know how the day went without adding anything up",
                    "One screen showing today's sales, cash in hand and what is selling, updating as your "
                            + "cashiers work. Open it any time and you already know where the shop stands.",
                    List.of(
                            "See today's takings while the day is still running, not tomorrow morning",
                            "Spot a quiet afternoon early enough to do something about it",
                            "Compare today against yesterday and last week at a glance",
                            "Check on the shop from your phone without calling the counter"))),

            Map.entry("POS", new ModulePitch(
                    "Serve a customer in seconds, even when the internet drops",
                    "A checkout screen built for one hand and a barcode scanner, with printed receipts and "
                            + "every payment type. It keeps working when the connection does not.",
                    List.of(
                            "Scan, take payment and print a receipt in a few seconds",
                            "Split a bill across cash, card and credit without leaving the screen",
                            "Keep selling through an internet outage and sync when it returns",
                            "Train a new cashier on it in an afternoon"))),

            Map.entry("SALES", new ModulePitch(
                    "Find any past bill in seconds when a customer walks back in",
                    "Every invoice you have ever issued, searchable by number, customer, date or item, with "
                            + "a reprint on the same screen.",
                    List.of(
                            "Pull up a two-month-old bill while the customer is still at the counter",
                            "Reprint a lost receipt instead of arguing about it",
                            "See exactly what a customer bought and when",
                            "Check what a cashier sold during any shift"))),

            Map.entry("PROMOTIONS", new ModulePitch(
                    "Run a discount without trusting anyone to remember it",
                    "Set the rule once and the POS applies it automatically at checkout. No mental "
                            + "arithmetic, no cashier deciding the price, no arguments.",
                    List.of(
                            "Run buy-one-get-one and bundle offers that apply themselves",
                            "Set happy-hour pricing that switches on and off by the clock",
                            "Stop discounts being given away by mistake or by favour",
                            "See afterwards exactly what an offer cost you and what it brought in"))),

            Map.entry("WARRANTIES", new ModulePitch(
                    "End the argument about whether something is still under warranty",
                    "Issue a warranty at checkout and it is on the bill and in the system. When the customer "
                            + "returns months later, the answer takes one search.",
                    List.of(
                            "Print warranty terms on the receipt automatically",
                            "Look up any warranty by bill number or customer",
                            "Track open claims so none quietly goes unanswered",
                            "Show a customer the exact terms they agreed to"))),

            Map.entry("ITEMS", new ModulePitch(
                    "Your whole catalogue, priced and organised",
                    "Every product with its price, barcode and category, so the POS can find anything "
                            + "instantly and your prices stay consistent across branches.",
                    List.of(
                            "Change a price once and it applies everywhere",
                            "Scan a barcode and the right item comes up every time",
                            "Group products so staff can find them without the barcode",
                            "Keep cost and selling price on the same record"))),

            Map.entry("STOCK", new ModulePitch(
                    "Find out what you have actually got, not what you think you have",
                    "Live stock levels that move with every sale, with a full history for each item. The "
                            + "difference between the shelf and the system stops being a mystery.",
                    List.of(
                            "See what is running out before a customer finds out for you",
                            "Trace where every unit went — sold, adjusted, transferred or wasted",
                            "Count the shelf and correct the system in one screen",
                            "Stop money sitting dead in stock you forgot you ordered"))),

            Map.entry("PURCHASES", new ModulePitch(
                    "Know what you owe each supplier and what you really paid",
                    "Record every delivery against its supplier and cost price, so your margins are real "
                            + "numbers instead of estimates and nothing gets paid twice.",
                    List.of(
                            "See what you owe each supplier without checking a notebook",
                            "Catch a delivery that is short before you pay for it",
                            "Know your true cost per item, so your margin is real",
                            "Send goods back with a debit note instead of a phone call"))),

            Map.entry("SUPPLIERS", new ModulePitch(
                    "One place for every supplier, what they owe and what you owe them",
                    "Contact details, purchase history and outstanding balance per supplier — so you walk "
                            + "into a negotiation knowing the numbers.",
                    List.of(
                            "See a supplier's whole history before you call them",
                            "Know your outstanding balance with each one",
                            "Compare what different suppliers charge for the same item",
                            "Keep contact details where your staff can find them"))),

            Map.entry("CUSTOMERS", new ModulePitch(
                    "Turn a face at the counter into a customer you actually know",
                    "A record for every regular with what they buy and what they owe, so you can serve them "
                            + "faster and chase credit without guessing.",
                    List.of(
                            "See a customer's buying history the moment you pick their name",
                            "Know who owes you money and how long it has been",
                            "Find your best customers instead of assuming who they are",
                            "Keep notes so any staff member can pick up the conversation"))),

            Map.entry("SHIFTS", new ModulePitch(
                    "Close the till knowing whether the cash is right",
                    "Each cashier opens and closes their own shift with a counted declaration, and the "
                            + "system tells you the difference immediately.",
                    List.of(
                            "See over and short the moment a shift closes, not days later",
                            "Know exactly who was on the till when something went wrong",
                            "Stop cash differences being quietly absorbed",
                            "Hand over between shifts with a number both sides agree on"))),

            Map.entry("EXPENSES", new ModulePitch(
                    "Account for the money that leaves the till, not just what comes in",
                    "Record every payment out — transport, repairs, tea, wages — against a category, so "
                            + "your profit is what is actually left.",
                    List.of(
                            "See where the small daily spending really goes",
                            "Stop the till being short with nobody able to explain it",
                            "Get a true profit figure with costs already taken out",
                            "Categorise spending so the pattern becomes obvious"))),

            Map.entry("CASH_DROPS", new ModulePitch(
                    "Move cash to the bank with a record at both ends",
                    "Log every amount taken out of the till for banking or safekeeping, against the account "
                            + "it went to, so the trail never breaks.",
                    List.of(
                            "Know how much cash left the till and who took it",
                            "Match your banking against the till without guessing",
                            "Keep large amounts out of the drawer safely",
                            "Give your accountant a clean record instead of a shoebox"))),

            Map.entry("REPORTS", new ModulePitch(
                    "Stop guessing which products actually make you money",
                    "Reports across sales, stock, cash, customers and suppliers — the numbers that tell you "
                            + "what to reorder, what to drop, and where the money went.",
                    List.of(
                            "See true profit per item, not just what sold the most",
                            "Catch slow movers before they tie up cash on the shelf",
                            "Know which cashier, branch and hour brings in the most",
                            "Get any report as a file, or have it emailed on a schedule"))),

            Map.entry("SETTINGS", new ModulePitch(
                    "Make the system work the way your shop works",
                    "Configure receipts, branches, staff logins and shop rules so the software fits your "
                            + "counter instead of the other way round.",
                    List.of(
                            "Put your own name, logo and terms on every receipt",
                            "Give each staff member exactly the access they need",
                            "Set the rules once and have every branch follow them",
                            "Change how the shop runs without calling for support")))
    );

    /** Copy for a module, or {@code null} when it has none — the caller falls back to the description. */
    public static ModulePitch forModule(String moduleKey) {
        if (moduleKey == null) {
            return null;
        }
        ModulePitch direct = PITCHES.get(moduleKey);
        if (direct != null) {
            return direct;
        }
        // A sub-feature inherits its parent's pitch: the reason to buy "Branch transfers" is
        // the reason to buy Stock control, and writing 36 more of these would produce filler.
        ModuleDefinition definition = ModuleCatalog.byKey(moduleKey);
        if (definition != null && definition.parentKey() != null) {
            return PITCHES.get(definition.parentKey());
        }
        return null;
    }
}
