package net.bandit.flash_flood;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/** {@code /flashflood start|stop|status} — operator command for triggering and inspecting floods. */
public final class FloodCommand {
    /** Rain length when a flood is started by command and it isn't already raining (10 minutes). */
    private static final int COMMAND_RAIN_TICKS = 12000;

    private FloodCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("flashflood")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("start").executes(FloodCommand::start))
                .then(Commands.literal("stop").executes(FloodCommand::stop))
                .then(Commands.literal("status").executes(FloodCommand::status)));
    }

    private static int start(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        if (!FloodManager.canFlood(level)) {
            ctx.getSource().sendFailure(Component.literal("This dimension has no weather, so it can't flood."));
            return 0;
        }
        FloodData data = FloodData.get(level);
        if (data.isFloodActive()) {
            ctx.getSource().sendFailure(Component.literal("A flood is already active."));
            return 0;
        }
        if (!level.getLevelData().isRaining()) {
            level.setWeatherParameters(0, COMMAND_RAIN_TICKS, true, false);
        }
        // Mark rain as already seen so the rain-start roll doesn't run on top of this.
        data.setWasRaining(true);
        FloodManager.startFlood(level, data);
        ctx.getSource().sendSuccess(() -> Component.literal("Flood started."), true);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        FloodData data = FloodData.get(level);
        if (!data.isFloodActive()) {
            ctx.getSource().sendFailure(Component.literal("No flood is active."));
            return 0;
        }
        FloodManager.stopFlood(level, data);
        ctx.getSource().sendSuccess(() -> Component.literal("Flood stopped; water will drain."), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        FloodData data = FloodData.get(level);
        String state = data.isFloodActive()
                ? "active, water up to +" + FloodManager.currentRise(data) + " blocks"
                : (data.hasWork() ? "draining" : "none");
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Flood: " + state + ". Flood water blocks: " + data.size()), false);
        return 1;
    }
}
