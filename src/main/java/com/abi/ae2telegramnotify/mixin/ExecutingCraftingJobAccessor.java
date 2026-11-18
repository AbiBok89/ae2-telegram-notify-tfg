package com.abi.ae2telegramnotify.mixin;

import appeng.api.stacks.GenericStack;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.execution.ExecutingCraftingJob;
import javax.annotation.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ExecutingCraftingJob.class, remap = false)
public interface ExecutingCraftingJobAccessor {

    @Accessor("playerId")
    @Nullable
    Integer ae2tg$getPlayerId();

    @Accessor("finalOutput")
    GenericStack ae2tg$getFinalOutput();

    @Accessor("timeTracker")
    ElapsedTimeTracker ae2tg$getTimeTracker();
}
