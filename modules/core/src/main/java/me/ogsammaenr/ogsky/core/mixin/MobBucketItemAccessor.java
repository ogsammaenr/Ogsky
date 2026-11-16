package me.ogsammaenr.ogsky.core.mixin;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.MobBucketItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MobBucketItem.class)
public interface MobBucketItemAccessor {
	@Accessor("type")
	EntityType<? extends Mob> ogsky$getEntityType();
}
