package net.infiniteimperm.fabric.tagger.mixin;

import net.infiniteimperm.fabric.tagger.diagnose.JfrController;
import net.minecraft.client.gl.CompiledShader;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShaderProgram.class)
abstract class ShaderProgramMixin {
    @Unique
    private static final ThreadLocal<Long> INSIGNIA_SHADER_START_NS = new ThreadLocal<>();

    @Inject(method = "create", at = @At("HEAD"))
    private static void insignia$beforeShaderCreate(CompiledShader vertexShader, CompiledShader fragmentShader, VertexFormat format, CallbackInfoReturnable<ShaderProgram> cir) {
        JfrController.onVanillaShaderHookSeen();
        INSIGNIA_SHADER_START_NS.set(System.nanoTime());
    }

    @Inject(method = "create", at = @At("RETURN"))
    private static void insignia$afterShaderCreate(CompiledShader vertexShader, CompiledShader fragmentShader, VertexFormat format, CallbackInfoReturnable<ShaderProgram> cir) {
        Long startNs = INSIGNIA_SHADER_START_NS.get();
        if (startNs != null) {
            JfrController.onShaderCompileBatch(1, Math.max(0L, System.nanoTime() - startNs));
        }
        INSIGNIA_SHADER_START_NS.remove();
    }

    @Inject(method = "create", at = @At("TAIL"))
    private static void insignia$cleanupAfterShaderCreate(CompiledShader vertexShader, CompiledShader fragmentShader, VertexFormat format, CallbackInfoReturnable<ShaderProgram> cir) {
        INSIGNIA_SHADER_START_NS.remove();
    }
}
