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
            .then(ClientCommandManager.literal("privacy")
                .executes(context -> {
                    DiagnoseOrchestrator.getInstance().start(DiagnoseOrchestrator.Mode.PRIVACY, context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("time", IntegerArgumentType.integer(5, 3600))
                    .executes(context -> {
                        int seconds = IntegerArgumentType.getInteger(context, "time");
                        DiagnoseOrchestrator.getInstance().start(DiagnoseOrchestrator.Mode.PRIVACY, seconds, context.getSource());
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
        );
    }
}
