package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.chronicle.DiaryEntry;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.gametest.InnGameTests.Inn;
import com.gilfort.civmod.inn.InnRules;
import com.gilfort.civmod.inn.Inns;
import com.gilfort.civmod.inn.VisitorOffer;
import com.gilfort.civmod.inn.VisitorPrices;
import com.gilfort.civmod.job.PlayerTalk;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.menu.SettlerInfo;
import com.gilfort.civmod.menu.SettlerMenu;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.origin.OriginLine;
import com.gilfort.civmod.origin.Talent;
import com.gilfort.civmod.registry.ModDiaryEntryTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.SettlementData;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for talking to a visitor and hiring it (vault {@code Systems/NPC Population.md}, section 6): the price from the table
 * and the default tag, the first line of a talk with the price and the trade, the hire with the price stack and its refusals, and
 * the button that asks a visitor to stay longer. The inn is set up with the helpers of {@link InnGameTests}.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class VisitorHireGameTests {

    private VisitorHireGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "visitor_price_table", VisitorHireGameTests::priceTable);
            register(helper, "visitor_offer_trade_and_level", VisitorHireGameTests::offerTradeAndLevel);
            register(helper, "visitor_talk", VisitorHireGameTests::talk);
            register(helper, "visitor_hire", VisitorHireGameTests::hire);
            register(helper, "visitor_hire_refused", VisitorHireGameTests::hireRefused);
            register(helper, "visitor_clicks", VisitorHireGameTests::clicks);
            register(helper, "visitor_ask_button", VisitorHireGameTests::askButton);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ------------------------------------------------------------------------------------------------------------ price

    /**
     * The price of a trade: the table gives item and base amount, the amount is base x (1 + level / 10) rounded and at most one
     * stack, a Specialization without an entry asks what its Base Job asks, and a Job without any entry falls back to the default tag.
     */
    private static void priceTable(GameTestHelper helper) {
        UUID visitor = UUID.randomUUID();
        VisitorPrices.Price smith = VisitorPrices.price(ModJobs.SMITH.get(), 1, visitor);
        helper.assertTrue(smith.item() == Items.ANVIL && smith.amount() == 2, "A smith of level 1 asks 2 anvils: " + smith);
        helper.assertTrue(VisitorPrices.price(ModJobs.SMITH.get(), 5, visitor).amount() == 3, "Level 5 asks 3 anvils");
        helper.assertTrue(VisitorPrices.price(ModJobs.SMITH.get(), 10, visitor).amount() == 4, "Level 10 asks 4 anvils");
        VisitorPrices.Price fisher = VisitorPrices.price(ModJobs.FISHERMAN.get(), 1, visitor);
        helper.assertTrue(fisher.item() == Items.FISHING_ROD, "A fisher asks for a rod: " + fisher);

        helper.assertTrue(VisitorPrices.scaledAmount(40, 10, 64) == 64, "80 would be more than a stack: " + VisitorPrices.scaledAmount(40, 10, 64));
        helper.assertTrue(VisitorPrices.scaledAmount(1, 1, 64) == 1, "At least one item is asked for");
        helper.assertTrue(VisitorPrices.scaledAmount(3, 1, 64) == 3, "3.3 rounds to 3");
        helper.assertTrue(VisitorPrices.scaledAmount(3, 5, 64) == 5, "4.5 rounds up to 5");
        helper.assertTrue(VisitorPrices.scaledAmount(5, 10, 16) == 10, "Under the stack size nothing is cut");

        VisitorPrices.Price plantation = VisitorPrices.price(ModJobs.PLANTATION_FARMER.get(), 1, visitor);
        VisitorPrices.Price gatherer = VisitorPrices.price(ModJobs.GATHERER.get(), 1, visitor);
        helper.assertTrue(!VisitorPrices.entries().containsKey(ModJobs.PLANTATION_FARMER.get().id()) && plantation.equals(gatherer),
                "A Specialization without an entry asks what its Base Job asks: " + plantation + " and " + gatherer);

        VisitorPrices.Price fallback = VisitorPrices.price(ModJobs.DELIVERYMAN.get(), 1, visitor);
        helper.assertTrue(VisitorPrices.entry(ModJobs.DELIVERYMAN.get()).isEmpty(), "The Deliveryman has no entry");
        helper.assertTrue(fallback.item() == Items.EMERALD && fallback.amount() == InnRules.get().defaultPriceAmount(),
                "A Job without an entry asks the default: " + fallback);
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ offer

    /** A visitor in an inn with one guest bed and a Tavern Host, ready to be talked to. */
    private static Settler arrived(GameTestHelper helper, Inn inn) {
        InnGameTests.play(helper, inn, InnGameTests.today(helper), InnGameTests.NOON);
        List<Settler> visitors = InnGameTests.visitors(helper);
        helper.assertTrue(visitors.size() == 1, "One visitor came: " + visitors.size());
        return visitors.get(0);
    }

    /** The favoured Jobs of a visitor: its trade is the one it is best in, and its price follows the level it has there. */
    private static void offerTradeAndLevel(GameTestHelper helper) {
        InnRules.use(InnGameTests.rules(1.0, 0.0, 3));
        Inn inn = InnGameTests.inn(helper, 1, 1, true);
        Settler visitor = arrived(helper, inn);
        List<Job> favoured = new ArrayList<>();
        for (Job job : ModJobs.REGISTRY) {
            if (Talent.favours(visitor, job)) {
                favoured.add(job);
                visitor.getSkills().set(job, new SettlerSkills.Skill(2, 0));
            }
        }
        helper.assertTrue(!favoured.isEmpty(), "The upbringing of a visitor favours at least one Job");
        Job best = favoured.get(favoured.size() - 1);
        visitor.getSkills().set(best, new SettlerSkills.Skill(7, 0));
        VisitorOffer offer = VisitorOffer.of(visitor);
        helper.assertTrue(offer.trade() == best && offer.level() == 7, "The trade is the favoured Job with the highest level: " + offer);
        helper.assertTrue(offer.price().equals(VisitorPrices.price(best, 7, visitor.getUUID())), "The price follows the level: " + offer.price());

        for (Job job : favoured) {
            visitor.getSkills().set(job, new SettlerSkills.Skill(1, 0));
        }
        helper.assertTrue(VisitorOffer.of(visitor).trade() == favoured.get(0), "With a tie the first Job of the registry is the trade");
        for (Job job : ModJobs.REGISTRY) {
            if (!favoured.contains(job)) {
                visitor.getSkills().set(job, new SettlerSkills.Skill(9, 0));
            }
        }
        helper.assertTrue(favoured.contains(VisitorOffer.of(visitor).trade()), "A Job the upbringing does not favour is never the trade");
        InnGameTests.end(helper, inn);
    }

    // ------------------------------------------------------------------------------------------------------------- talk

    private static PlayerTalk.Result talk(GameTestHelper helper, Settler visitor, ServerPlayer player) {
        return PlayerTalk.talk(visitor, player).orElseThrow(() -> helper.assertionException("The visitor did not talk"));
    }

    /** The words of a chat line "Name: text" as the text. */
    private static Component textOf(Component chatLine) {
        if (chatLine.getContents() instanceof TranslatableContents contents && contents.getKey().equals("civmod.talk.line")
                && contents.getArgs()[1] instanceof Component text) {
            return text;
        }
        throw new IllegalStateException("Not a talk line: " + chatLine);
    }

    private static boolean isPriceLine(Component text) {
        return text.getContents() instanceof TranslatableContents contents && contents.getKey().equals("civmod.visitor.talk.price");
    }

    /**
     * The first line of a talk names the price and the trade and never the level. The three parts of the origin follow, one per
     * talk and each once, with their diary entries; the price line comes with every talk and stands alone once the origin is told.
     * A visitor gets no Entertainment from being talked to.
     */
    private static void talk(GameTestHelper helper) {
        InnRules.use(InnGameTests.rules(1.0, 0.0, 3));
        Inn inn = InnGameTests.inn(helper, 1, 1, true);
        List<Component> chat = new ArrayList<>();
        ServerPlayer player = InnGameTests.listener(helper, chat);
        Settler visitor = arrived(helper, inn);
        VisitorOffer offer = VisitorOffer.of(visitor);
        double entertainment = visitor.getWork().entertainment().value();

        Set<OriginLine.Slot> told = new HashSet<>();
        for (int talk = 1; talk <= 3; talk++) {
            chat.clear();
            PlayerTalk.Result result = talk(helper, visitor, player);
            helper.assertTrue(chat.size() == 2, "Talk " + talk + " says the price line and one part of the origin: " + chat);
            Component price = textOf(chat.get(0));
            helper.assertTrue(isPriceLine(price), "The first line is the price line: " + price);
            TranslatableContents contents = (TranslatableContents) price.getContents();
            helper.assertTrue(contents.getArgs()[0].equals(offer.price().amount()) && contents.getArgs()[1].equals(offer.price().stack().getItemName())
                    && contents.getArgs()[2].equals(offer.trade().indefiniteName()), "It names the price and the trade, and no level: " + price);
            helper.assertTrue(!isPriceLine(textOf(chat.get(1))) && result.told().isPresent() && told.add(result.told().get()),
                    "A part of the origin follows, each part once: " + result.told());
        }
        helper.assertTrue(told.size() == 3, "Place, upbringing and turning point are told: " + told);

        chat.clear();
        PlayerTalk.Result fourth = talk(helper, visitor, player);
        helper.assertTrue(fourth.told().isEmpty() && chat.size() == 1 && isPriceLine(textOf(chat.get(0))),
                "Once the origin is told, a talk gives the price line again: " + chat);
        helper.assertTrue(isPriceLine(fourth.line()), "The result is the price line");

        long origin = visitor.getPast().chronicle().entries().stream().map(DiaryEntry::type)
                .filter(type -> type.equals(ModDiaryEntryTypes.ORIGIN_LINE.getId())).count();
        helper.assertTrue(origin == 3, "The three told parts are in the diary: " + visitor.getPast().chronicle().entries());
        helper.assertTrue(visitor.getWork().entertainment().value() == entertainment, "A talk gives a visitor no Entertainment");
        TestPlayers.remove(player);
        InnGameTests.end(helper, inn);
    }

    // ------------------------------------------------------------------------------------------------------------- hire

    /** The skill cap a test gives to EXP gains: high enough that nothing is cut. */
    private static final int HIGH_CAP = 100;
    /** A free bed of the village near the Pantry (head position, relative to the test). */
    private static final BlockPos NEAR_BED = new BlockPos(1, 1, 6);
    /** A free bed of the village further from the Pantry. */
    private static final BlockPos FAR_BED = new BlockPos(9, 1, 8);

    private record Players(ServerPlayer player, List<Component> chat, List<Component> overlay) {
    }

    private static Players players(GameTestHelper helper) {
        List<Component> chat = new ArrayList<>();
        List<Component> overlay = new ArrayList<>();
        ServerPlayer player = TestPlayers.add(helper, Vec3.atCenterOf(helper.absolutePos(InnGameTests.PANTRY)), chat::add, overlay::add);
        // The test server's default game mode is creative, where a stack is never used up.
        player.setGameMode(GameType.SURVIVAL);
        return new Players(player, chat, overlay);
    }

    /** The player right-clicks the visitor with a stack in the main hand. */
    private static void click(ServerPlayer player, Settler visitor, ItemStack held, boolean sneaking) {
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        player.setShiftKeyDown(sneaking);
        player.interactOn(visitor, InteractionHand.MAIN_HAND, Vec3.ZERO);
    }

    /** Raises the visitor in its trade to level 10, so that its price asks for at least two items. */
    private static VisitorOffer expensive(Settler visitor) {
        Job trade = VisitorOffer.of(visitor).trade();
        visitor.getSkills().set(trade, new SettlerSkills.Skill(10, 0));
        return VisitorOffer.of(visitor);
    }

    private static boolean says(List<Component> lines, String key) {
        return InnGameTests.says(lines, key);
    }

    /** Takes a hired visitor out of the test settlement again. */
    private static void release(GameTestHelper helper, Inn inn, Settler hired) {
        SettlementData data = SettlementData.get(helper.getLevel());
        inn.settlement().bedOf(hired.getUUID()).ifPresent(bed -> data.releaseBed(inn.settlement(), bed));
        data.removeMember(inn.settlement(), hired.getUUID());
        hired.discard();
    }

    /**
     * The player hands over the price stack: the same entity becomes a settler of the settlement, takes the free village bed nearest
     * to the Pantry, leaves its guest bed free, keeps its diary and gets a "joined" entry with the reason it came. What is left of
     * the stack stays with the player, and the talent of the visitor works like that of every settler.
     */
    private static void hire(GameTestHelper helper) {
        InnRules.use(InnGameTests.rules(1.0, 0.0, 3));
        Inn inn = InnGameTests.inn(helper, 1, 1, true);
        BlockPos nearBed = VillagerConversionGameTests.bed(helper, NEAR_BED);
        VillagerConversionGameTests.bed(helper, FAR_BED);
        Players players = players(helper);
        Settler visitor = arrived(helper, inn);
        UUID id = visitor.getUUID();
        for (int talk = 0; talk < 3; talk++) {
            talk(helper, visitor, players.player());
        }
        VisitorOffer offer = expensive(visitor);
        BlockPos guestBed = inn.settlement().inn().visits().get(id).bed().orElseThrow();

        click(players.player(), visitor, new ItemStack(offer.price().item(), offer.price().amount() + 2), false);

        helper.assertTrue(!visitor.isRemoved() && visitor.getUUID().equals(id) && !visitor.isVisitor(), "The same entity is a settler now");
        helper.assertTrue(inn.settlement().members().contains(id) && visitor.getSettlementId() == inn.settlement().id(), "It is a member of the settlement");
        helper.assertTrue(players.player().getMainHandItem().getCount() == 2, "The price was handed over, two items are left: " + players.player().getMainHandItem());
        helper.assertTrue(inn.settlement().bedOf(id).equals(Optional.of(nearBed)), "It takes the free bed nearest to the Pantry: " + inn.settlement().bedOf(id));
        helper.assertTrue(inn.settlement().inn().visits().isEmpty() && inn.settlement().inn().visitIn(guestBed).isEmpty(), "Its stay is over and the guest bed is free");
        helper.assertTrue(inn.settlement().inn().isGuestBed(guestBed), "The guest bed is still a guest bed");
        helper.assertTrue(InnGameTests.visitors(helper).isEmpty(), "No visitor is left");

        Identifier type = ModDiaryEntryTypes.JOINED.getId();
        List<DiaryEntry> diary = visitor.getPast().chronicle().entries();
        Identifier reason = visitor.getPast().origin().orElseThrow().reason().orElseThrow();
        helper.assertTrue(diary.stream().anyMatch(entry -> entry.type().equals(type) && entry.params().equals(List.of(reason.toString()))),
                "The diary says it joined, with the reason it came: " + diary);
        helper.assertTrue(diary.stream().filter(entry -> entry.type().equals(ModDiaryEntryTypes.ORIGIN_LINE.getId())).count() == 3
                && diary.stream().anyMatch(entry -> entry.type().equals(ModDiaryEntryTypes.ARRIVAL.getId())),
                "It keeps the entries it already had: " + diary);

        helper.assertTrue(Talent.favours(visitor, offer.trade()), "Its talent is that of a settler");
        visitor.getSkills().set(offer.trade(), new SettlerSkills.Skill(1, 0));
        int exp = 0;
        for (int action = 0; action < 4; action++) {
            exp += Talent.gainExp(visitor, offer.trade(), 1, HIGH_CAP).exp();
        }
        helper.assertTrue(exp == 5, "Four actions of 1 EXP give 5 EXP in a favoured Job: " + exp);

        TestPlayers.remove(players.player());
        release(helper, inn, visitor);
        InnGameTests.end(helper, inn);
    }

    /**
     * A visitor that cannot be hired shakes its head, names the reason and leaves the stack with the player: no free bed in the
     * village (the guest bed does not count), a settlement at its limit, and too few items. With the bed, room and the full price
     * the same click hires it.
     */
    private static void hireRefused(GameTestHelper helper) {
        InnRules.use(InnGameTests.rules(1.0, 0.0, 3));
        Inn inn = InnGameTests.inn(helper, 1, 1, true);
        Players players = players(helper);
        Settler visitor = arrived(helper, inn);
        VisitorOffer offer = expensive(visitor);
        int amount = offer.price().amount();
        helper.assertTrue(amount >= 2, "A level 10 trade asks for at least two items: " + offer.price());

        click(players.player(), visitor, offer.price().stack(), false);
        helper.assertTrue(says(players.overlay(), "civmod.visitor.hire.no_bed"), "Only the guest bed is free: " + players.overlay());
        helper.assertTrue(stillWaiting(helper, inn, visitor, players, amount), "The visitor stays a visitor and the player keeps the stack");

        BlockPos bed = VillagerConversionGameTests.bed(helper, NEAR_BED);
        int limit = CivModConfig.MAX_SETTLERS_PER_SETTLEMENT.get();
        try {
            players.overlay().clear();
            CivModConfig.MAX_SETTLERS_PER_SETTLEMENT.set(1);
            click(players.player(), visitor, offer.price().stack(), false);
            helper.assertTrue(says(players.overlay(), "civmod.visitor.hire.full"), "The Tavern Host fills a settlement of one: " + players.overlay());
            helper.assertTrue(stillWaiting(helper, inn, visitor, players, amount), "A full settlement keeps the stack with the player");

            players.overlay().clear();
            CivModConfig.MAX_SETTLERS_PER_SETTLEMENT.set(2);
            click(players.player(), visitor, new ItemStack(offer.price().item(), amount - 1), false);
            helper.assertTrue(says(players.overlay(), "civmod.visitor.hire.not_enough"), "One item too few: " + players.overlay());
            helper.assertTrue(stillWaiting(helper, inn, visitor, players, amount - 1), "Too few items stay with the player");

            players.overlay().clear();
            click(players.player(), visitor, offer.price().stack(), false);
            helper.assertTrue(!visitor.isVisitor() && inn.settlement().bedOf(visitor.getUUID()).equals(Optional.of(bed)),
                    "With a free bed and room for one more the price hires it: " + players.overlay());
            helper.assertTrue(players.player().getMainHandItem().isEmpty(), "The exact price is used up");
        } finally {
            CivModConfig.MAX_SETTLERS_PER_SETTLEMENT.set(limit);
        }
        TestPlayers.remove(players.player());
        release(helper, inn, visitor);
        InnGameTests.end(helper, inn);
    }

    /** Whether the visitor is still a visitor with its stay and the player still holds a stack of that size. */
    private static boolean stillWaiting(GameTestHelper helper, Inn inn, Settler visitor, Players players, int held) {
        return visitor.isVisitor() && inn.settlement().inn().visits().containsKey(visitor.getUUID()) && !inn.settlement().members().contains(visitor.getUUID())
                && players.player().getMainHandItem().getCount() == held;
    }

    // ------------------------------------------------------------------------------------------------------ the clicks

    /**
     * Whether a click opens the window of the visitor. The test player's connection goes nowhere and cannot take the packet that
     * opens a screen, so an attempt to open one shows as the error of that packet.
     */
    private static boolean opensWindow(ServerPlayer player, Settler visitor, ItemStack held, boolean sneaking) {
        try {
            click(player, visitor, held, sneaking);
            return false;
        } catch (RuntimeException e) {
            if (String.valueOf(e.getMessage()).contains("open_screen")) {
                return true;
            }
            throw e;
        }
    }

    /**
     * Shift + right-click with an empty hand talks to a visitor and does not ask it to stay. A plain right-click opens its window,
     * and so does an item that is not the price (food included, which a visitor does not eat).
     */
    private static void clicks(GameTestHelper helper) {
        InnRules.use(InnGameTests.rules(1.0, 0.0, 3));
        Inn inn = InnGameTests.inn(helper, 1, 1, true);
        Players players = players(helper);
        Settler visitor = arrived(helper, inn);

        click(players.player(), visitor, ItemStack.EMPTY, true);
        helper.assertTrue(players.chat().size() == 2 && isPriceLine(textOf(players.chat().get(0))), "Shift + right-click talks: " + players.chat());
        helper.assertTrue(!inn.settlement().inn().visits().get(visitor.getUUID()).asked() && players.overlay().isEmpty(),
                "It does not ask the visitor to stay: " + players.overlay());
        helper.assertTrue(!opensWindow(players.player(), visitor, ItemStack.EMPTY, true), "Shift + right-click opens no window");

        helper.assertTrue(opensWindow(players.player(), visitor, ItemStack.EMPTY, false), "A plain right-click opens the window");
        helper.assertTrue(!inn.settlement().inn().visits().get(visitor.getUUID()).asked(), "Opening the window asks nothing");

        ItemStack bread = new ItemStack(Items.BREAD, 3);
        helper.assertTrue(opensWindow(players.player(), visitor, bread, false) && bread.getCount() == 3, "Food is not eaten, the window opens");
        helper.assertTrue(opensWindow(players.player(), visitor, new ItemStack(Items.DIRT), true), "Shift with an item in the hand opens the window");
        TestPlayers.remove(players.player());
        InnGameTests.end(helper, inn);
    }

    /**
     * The window of a visitor offers the one ask to stay longer as a button. The button asks once, tells the player the answer, and
     * the window knows afterwards that the ask is used up.
     */
    private static void askButton(GameTestHelper helper) {
        InnRules.use(InnGameTests.rules(1.0, 0.0, 2));
        Inn inn = InnGameTests.inn(helper, 1, 1, true);
        Inns.script(MasteryRolls.scripted(MasteryRolls.HIT));
        Players players = players(helper);
        Settler visitor = arrived(helper, inn);
        long leaveSlot = inn.settlement().inn().visits().get(visitor.getUUID()).leaveSlot();
        helper.assertTrue(SettlerInfo.of(visitor).visiting() == SettlerInfo.Visiting.CAN_ASK, "The window offers the ask: " + SettlerInfo.of(visitor).visiting());

        SettlerMenu menu = new SettlerMenu(1, players.player().getInventory(), visitor, SettlerInfo.of(visitor));
        helper.assertTrue(menu.getInfo().visiting() == SettlerInfo.Visiting.CAN_ASK, "The open window offers the ask");
        menu.clickMenuButton(players.player(), SettlerMenu.BUTTON_ASK_STAY);
        helper.assertTrue(says(players.overlay(), "civmod.visitor.stay.extended"), "The visitor agrees on a hit: " + players.overlay());
        helper.assertTrue(inn.settlement().inn().visits().get(visitor.getUUID()).leaveSlot() > leaveSlot, "It stays longer");
        helper.assertTrue(menu.getInfo().visiting() == SettlerInfo.Visiting.ASKED, "The window shows the ask as used");

        players.overlay().clear();
        menu.clickMenuButton(players.player(), SettlerMenu.BUTTON_ASK_STAY);
        helper.assertTrue(says(players.overlay(), "civmod.visitor.stay.already_asked"), "A second ask is refused: " + players.overlay());

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        SettlerInfo.STREAM_CODEC.encode(buffer, SettlerInfo.of(visitor));
        helper.assertTrue(SettlerInfo.STREAM_CODEC.decode(buffer).visiting() == SettlerInfo.Visiting.ASKED, "The state survives the trip to the client");
        Inns.script(null);
        TestPlayers.remove(players.player());
        InnGameTests.end(helper, inn);
    }
}
