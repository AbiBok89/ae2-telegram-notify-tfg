package com.abi.ae2telegramnotify.mixin;

import appeng.core.sync.packets.CraftingJobStatusPacket;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.abi.ae2telegramnotify.handler.CraftingCompletionHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftingCpuLogic.class, remap = false)
public abstract class CraftingCpuLogicMixin {

    @Shadow
    @Final
    CraftingCPUCluster cluster;

    @Inject(method = "notifyJobOwner", at = @At("HEAD"))
    private void ae2tg$onNotifyJobOwner(
            ExecutingCraftingJob job,
            CraftingJobStatusPacket.Status status,
            CallbackInfo ci
    ) {
        if (status == CraftingJobStatusPacket.Status.FINISHED) {
            CraftingCompletionHandler.handleJobFinished(this.cluster, job);
        }
    }
}
