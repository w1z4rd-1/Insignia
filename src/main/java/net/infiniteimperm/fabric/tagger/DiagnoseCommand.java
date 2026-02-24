package net.infiniteimperm.fabric.tagger;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.infiniteimperm.fabric.tagger.diagnose.DiagnoseOrchestrator;

public final class DiagnoseCommand {
    private DiagnoseCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("diagnose")
            .executes(context -> {
                DiagnoseOrchestrator.getInstance().showSetupHelp(context.getSource());
                return 1;
            })
            .then(ClientCommandManager.literal("normal")
                .executes(context -> {
                    DiagnoseOrchestrator.getInstance().start(DiagnoseOrchestrator.Mode.NORMAL, context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("time", IntegerArgumentType.integer(5, 3600))
                    .executes(context -> {
                        int seconds = IntegerArgumentType.getInteger(context, "time");
                        DiagnoseOrchestrator.getInstance().start(DiagnoseOrchestrator.Mode.NORMAL, seconds, context.getSource());
                        return 1;
                    }))
            )
            .then(ClientCommandManager.literal("full")
                .executes(context -> {
                    DiagnoseOrchestrator.getInstance().start(DiagnoseOrchestrator.Mode.FULL, context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("time", IntegerArgumentType.integer(5, 3600))
                    .executes(context -> {
                        int seconds = IntegerArgumentType.getInteger(context, "time");
                        DiagnoseOrchestrator.getInstance().start(DiagnoseOrchestrator.Mode.FULL, seconds, context.getSource());
                        return 1;
                    }))
            )
            .then(ClientCommandManager.literal("custom")
                .executes(context -> {
                    DiagnoseOrchestrator.getInstance().start(DiagnoseOrchestrator.Mode.CUSTOM, context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("time", IntegerArgumentType.integer(5, 3600))
                    .executes(context -> {
                        int seconds = IntegerArgumentType.getInteger(context, "time");
                        DiagnoseOrchestrator.getInstance().start(DiagnoseOrchestrator.Mode.CUSTOM, seconds, context.getSource());
                        return 1;
                    }))
            )
        );
    }
}
