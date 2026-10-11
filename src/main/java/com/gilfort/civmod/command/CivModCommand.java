package com.gilfort.civmod.command;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.menu.DebugMenu;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * The {@code /civmod} command tree.
 * <p>
 * The {@code debug} branch is only registered in development environments.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class CivModCommand {

    /** Root literal of the command tree. */
    public static final String ROOT = "civmod";

    private CivModCommand() {
    }

    /**
     * Registers the command tree.
     *
     * @param event the command registration event
     */
    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT)
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        if (isDebugEnabled()) {
            root.then(Commands.literal("debug")
                    .then(Commands.literal("gui")
                            .executes(context -> {
                                openDebugMenu(context.getSource().getPlayerOrException());
                                return 1;
                            })));
        }
        event.getDispatcher().register(root);
    }

    /**
     * @return whether debug features are available (development environment only)
     */
    public static boolean isDebugEnabled() {
        return !FMLEnvironment.isProduction();
    }

    /**
     * Opens the debug GUI for a player.
     *
     * @param player the player
     */
    public static void openDebugMenu(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider((containerId, inventory, p) -> new DebugMenu(containerId, inventory),
                Component.translatable("civmod.gui.debug.title")));
    }
}
