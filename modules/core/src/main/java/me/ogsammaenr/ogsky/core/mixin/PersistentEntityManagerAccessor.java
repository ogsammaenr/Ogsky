package me.ogsammaenr.ogsky.core.mixin;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PersistentEntitySectionManager.class)
public interface PersistentEntityManagerAccessor<T extends EntityAccess> {
	@Accessor("permanentStorage")
	EntityPersistentStorage<T> ogsky$getPermanentStorage();

	@Accessor("sectionStorage")
	EntitySectionStorage<T> ogsky$getSectionStorage();
}
