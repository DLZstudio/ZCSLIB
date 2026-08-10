package zcslib.testplugin.mixin;

import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dummy Mixin class for testing M7 MixinAdapter.
 * NOT intended for actual injection — just validates the registration pipeline.
 */
@Mixin(ItemStack.class)
public abstract class DummyMixinItem {

    @Inject(method = "split", at = @At("HEAD"), cancellable = true)
    private void onSplit(int count, CallbackInfoReturnable<ItemStack> cir) {
        // No-op: dummy Mixin for pipeline verification only
    }
}
