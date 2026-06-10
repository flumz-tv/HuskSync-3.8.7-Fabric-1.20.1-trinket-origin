/*
 * This file is part of HuskSync, licensed under the Apache License 2.0.
 *
 *  Copyright (c) William278 <will27528@gmail.com>
 *  Copyright (c) contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.william278.husksync.data;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.annotations.SerializedName;
import lombok.*;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.advancement.PlayerAdvancementTracker;
//#if MC==12001
//$$ import net.minecraft.enchantment.EnchantmentHelper;
//$$ import net.minecraft.nbt.NbtCompound;
//#else
import net.minecraft.component.DataComponentTypes;
//#endif
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.StatType;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.william278.desertwell.util.ThrowingConsumer;
import net.william278.husksync.FabricHuskSync;
import net.william278.husksync.HuskSync;
import net.william278.husksync.adapter.Adaptable;
import net.william278.husksync.config.Settings.SynchronizationSettings.AttributeSettings;
//#if MC>=12104
import net.william278.husksync.mixins.HungerManagerMixin;
//#endif
import net.william278.husksync.user.FabricUser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import org.jetbrains.annotations.Unmodifiable;

import java.util.*;

import static net.william278.husksync.util.FabricKeyedAdapter.*;

public abstract class FabricData implements Data {

    @Override
    public void apply(@NotNull UserDataHolder user, @NotNull HuskSync plugin) {
        this.apply((FabricUser) user, (FabricHuskSync) plugin);
    }

    protected abstract void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin);

    @Getter
    public static abstract class Items extends FabricData implements Data.Items {

        private final @Nullable ItemStack @NotNull [] contents;

        private Items(@Nullable ItemStack @NotNull [] contents) {
            this.contents = Arrays.stream(contents.clone())
                    .map(i -> i == null || i.isEmpty() ? null : i)
                    .toArray(ItemStack[]::new);
        }

        @Nullable
        @Override
        public Stack @NotNull [] getStack() {
            return Arrays.stream(contents)
                    .map(stack -> stack != null ? new Stack(
                            stack.getItem().toString(),
                            stack.getCount(),
                            stack.getName().getString(),
                            //#if MC==12001
                            //$$ Optional.ofNullable(stack.getSubNbt(ItemStack.DISPLAY_KEY))
                            //$$         .flatMap(display -> Optional.ofNullable(display.get(ItemStack.LORE_KEY))
                            //$$                 .map(lore -> ((List<String>) lore).stream().toList()))
                            //$$         .orElse(null),
                            //$$ stack.getEnchantments().stream()
                            //$$        .map(element -> EnchantmentHelper.getIdFromNbt((NbtCompound) element))
                            //$$        .filter(Objects::nonNull).map(Identifier::toString)
                            //$$        .toList()
                            //#else
                            stack.getComponents().get(DataComponentTypes.LORE).lines().stream()
                                    .map(Text::getString)
                                    .toList(),
                            stack.getEnchantments().getEnchantments().stream()
                                    .map(RegistryEntry::getIdAsString)
                                    .filter(Objects::nonNull)
                                    .toList()
                            //#endif
                    ) : null)
                    .toArray(Stack[]::new);
        }

        @Override
        public void clear() {
            Arrays.fill(contents, null);
        }

        @Override
        public void setContents(@NotNull Data.Items contents) {
            this.setContents(((FabricData.Items) contents).getContents());
        }

        public void setContents(@Nullable ItemStack @NotNull [] contents) {
            // Ensure the array is the correct length for the inventory
            if (contents.length != this.contents.length) {
                contents = Arrays.copyOf(contents, this.contents.length);
            }
            System.arraycopy(contents, 0, this.contents, 0, this.contents.length);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof FabricData.Items items) {
                return Arrays.equals(contents, items.getContents());
            }
            return false;
        }

        @Setter
        @Getter
        public static class Inventory extends FabricData.Items implements Data.Items.Inventory {

            @Range(from = 0, to = 8)
            private int heldItemSlot;

            public Inventory(@Nullable ItemStack @NotNull [] contents, int heldItemSlot) {
                super(contents);
                this.heldItemSlot = heldItemSlot;
            }

            @NotNull
            public static FabricData.Items.Inventory from(@Nullable ItemStack @NotNull [] contents, int heldItemSlot) {
                return new FabricData.Items.Inventory(contents, heldItemSlot);
            }

            @NotNull
            public static FabricData.Items.Inventory from(@NotNull Collection<ItemStack> contents, int heldItemSlot) {
                return from(contents.toArray(ItemStack[]::new), heldItemSlot);
            }

            @NotNull
            public static FabricData.Items.Inventory empty() {
                return new FabricData.Items.Inventory(new ItemStack[INVENTORY_SLOT_COUNT], 0);
            }

            @Override
            public int getSlotCount() {
                return getContents().length;
            }

            @Override
            public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
                final ServerPlayerEntity player = user.getPlayer();
                //#if MC>=12104
                player.playerScreenHandler.getCraftingInput().clear();
                //#else
                //$$ player.playerScreenHandler.clearCraftingSlots();
                //#endif
                player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
                final ItemStack[] items = getContents();
                for (int slot = 0; slot < player.getInventory().size(); slot++) {
                    player.getInventory().setStack(slot, items[slot] == null ? ItemStack.EMPTY : items[slot]);
                }
                //#if MC<12105
                //$$ player.getInventory().selectedSlot = heldItemSlot;
                //#else
                player.getInventory().setSelectedSlot(heldItemSlot);
                //#endif
                player.playerScreenHandler.sendContentUpdates();
                player.getInventory().updateItems();
            }

        }

        public static class EnderChest extends FabricData.Items implements Data.Items.EnderChest {

            private EnderChest(@Nullable ItemStack @NotNull [] contents) {
                super(contents);
            }

            @NotNull
            public static FabricData.Items.EnderChest adapt(@Nullable ItemStack @NotNull [] contents) {
                return new FabricData.Items.EnderChest(contents);
            }

            @NotNull
            public static FabricData.Items.EnderChest adapt(@NotNull Collection<ItemStack> items) {
                return adapt(items.toArray(ItemStack[]::new));
            }

            @NotNull
            public static FabricData.Items.EnderChest empty() {
                return new FabricData.Items.EnderChest(new ItemStack[ENDER_CHEST_SLOT_COUNT]);
            }

            @Override
            public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
                final ItemStack[] items = getContents();
                for (int slot = 0; slot < user.getPlayer().getEnderChestInventory().size(); slot++) {
                    user.getPlayer().getEnderChestInventory().setStack(
                            slot, items[slot] == null ? ItemStack.EMPTY : items[slot]
                    );
                }
            }

        }

        public static class ItemArray extends FabricData.Items implements Data.Items {

            private ItemArray(@Nullable ItemStack @NotNull [] contents) {
                super(contents);
            }

            @NotNull
            public static ItemArray adapt(@NotNull Collection<ItemStack> drops) {
                return new ItemArray(drops.toArray(ItemStack[]::new));
            }

            @NotNull
            public static ItemArray adapt(@Nullable ItemStack @NotNull [] drops) {
                return new ItemArray(drops);
            }

            @Override
            public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
                throw new UnsupportedOperationException("A generic item array cannot be applied to a player");
            }

        }

    }

    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class PotionEffects extends FabricData implements Data.PotionEffects {

        private final Collection<StatusEffectInstance> effects;

        @NotNull
        public static FabricData.PotionEffects from(@NotNull Collection<StatusEffectInstance> sei) {
            return new FabricData.PotionEffects(Lists.newArrayList(sei.stream().filter(e -> !e.isAmbient()).toList()));
        }

        @NotNull
        public static FabricData.PotionEffects adapt(@NotNull Collection<Effect> effects) {
            return from(effects.stream()
                    .map(effect -> {
                        final StatusEffect type = matchEffectType(effect.type());
                        return type != null ? new StatusEffectInstance(
                                //#if MC==12001
                                //$$ type,
                                //#else
                                RegistryEntry.of(type),
                                //#endif
                                effect.duration(),
                                effect.amplifier(),
                                effect.isAmbient(),
                                effect.showParticles(),
                                effect.hasIcon()
                        ) : null;
                    })
                    .filter(Objects::nonNull)
                    .toList()
            );
        }

        @NotNull
        @SuppressWarnings("unused")
        public static FabricData.PotionEffects empty() {
            return new FabricData.PotionEffects(Lists.newArrayList());
        }

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
            final ServerPlayerEntity player = user.getPlayer();
            //#if MC==12001
            //$$ final List<StatusEffect> effectsToRemove = player.getActiveStatusEffects().entrySet().stream()
            //$$        .filter(e -> !e.getValue().isAmbient()).map(Map.Entry::getKey).toList();
            //$$ effectsToRemove.forEach(player::removeStatusEffect);
            //#else
            //todo ambient check
            final List<StatusEffect> effectsToRemove = new ArrayList<>(player.getActiveStatusEffects().keySet().stream()
                    .map(RegistryEntry::value).toList());
            effectsToRemove.forEach(effect -> player.removeStatusEffect(RegistryEntry.of(effect)));
            //#endif
            getEffects().forEach(player::addStatusEffect);
        }

        @NotNull
        @Override
        @Unmodifiable
        public List<Effect> getActiveEffects() {
            return effects.stream()
                    .map(potionEffect -> {
                        //#if MC==12001
                        //$$ final String key = getEffectId(potionEffect.getEffectType());
                        //#else
                        final String key = getEffectId(potionEffect.getEffectType().value());
                        //#endif
                        return key != null ? new Effect(
                                key,
                                potionEffect.getAmplifier(),
                                potionEffect.getDuration(),
                                potionEffect.isAmbient(),
                                potionEffect.shouldShowParticles(),
                                potionEffect.shouldShowIcon()
                        ) : null;
                    })
                    .filter(Objects::nonNull)
                    .toList();
        }

    }

    @Getter
    @Setter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Advancements extends FabricData implements Data.Advancements {

        private List<Advancement> completed;

        @NotNull
        public static FabricData.Advancements adapt(@NotNull ServerPlayerEntity player) {
            final MinecraftServer server = Objects.requireNonNull(player.getServer(), "Server is null");
            final List<Advancement> advancements = Lists.newArrayList();
            forEachAdvancementEntry(server, advancementEntry -> {
                final AdvancementProgress advancementProgress = player.getAdvancementTracker().getProgress(advancementEntry);
                final Map<String, Date> awardedCriteria = Maps.newHashMap();

                advancementProgress.getObtainedCriteria().forEach((criteria) -> awardedCriteria.put(
                        criteria,
                        //#if MC==12001
                        //$$ advancementProgress.getEarliestProgressObtainDate()
                        //#else
                        Date.from(advancementProgress.getEarliestProgressObtainDate())
                        //#endif
                ));

                // Only save the advancement if criteria has been completed
                if (!awardedCriteria.isEmpty()) {
                    advancements.add(Advancement.adapt(
                            //#if MC==12001
                            //$$ advancementEntry.getId().toString(),
                            //#else
                            advancementEntry.id().asString(),
                            //#endif
                            awardedCriteria
                    ));
                }
            });
            return new FabricData.Advancements(advancements);
        }

        @NotNull
        public static FabricData.Advancements from(@NotNull List<Advancement> advancements) {
            return new FabricData.Advancements(advancements);
        }

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
            final ServerPlayerEntity player = user.getPlayer();
            final MinecraftServer server = Objects.requireNonNull(player.getServer(), "Server is null");
            plugin.runAsync(() -> forEachAdvancementEntry(server, advancementEntry -> {
                final AdvancementProgress progress = player.getAdvancementTracker().getProgress(advancementEntry);
                final Optional<Advancement> record = completed.stream()
                        .filter(r -> r.getKey().equals(
                                //#if MC==12001
                                //$$ advancementEntry.getId().toString()
                                //#else
                                advancementEntry.id().asString()
                                //#endif
                        ))
                        .findFirst();
                if (record.isEmpty()) {
                    return;
                }

                final Map<String, Date> criteria = record.get().getCompletedCriteria();
                final List<String> awarded = Lists.newArrayList(progress.getObtainedCriteria());
                this.setAdvancement(
                        plugin, advancementEntry, player, user,
                        criteria.keySet().stream().filter(key -> !awarded.contains(key)).toList(),
                        awarded.stream().filter(key -> !criteria.containsKey(key)).toList()
                );
            }));
        }

        private void setAdvancement(@NotNull FabricHuskSync plugin,
                                    //#if MC==12001
                                    //$$ @NotNull net.minecraft.advancement.Advancement advancementEntry,
                                    //#else
                                    @NotNull net.minecraft.advancement.AdvancementEntry advancementEntry,
                                    //#endif
                                    @NotNull ServerPlayerEntity player,
                                    @NotNull FabricUser user,
                                    @NotNull List<String> toAward,
                                    @NotNull List<String> toRevoke) {
            plugin.runSync(() -> {
                // Track player exp level & progress
                final int expLevel = player.experienceLevel;
                final float expProgress = player.experienceProgress;

                // Award and revoke advancement criteria
                final PlayerAdvancementTracker progress = player.getAdvancementTracker();
                toAward.forEach(a -> progress.grantCriterion(advancementEntry, a));
                toRevoke.forEach(r -> progress.revokeCriterion(advancementEntry, r));

                // Restore player exp level & progress
                if (!toAward.isEmpty()
                        && (player.experienceLevel != expLevel || player.experienceProgress != expProgress)) {
                    player.setExperienceLevel(expLevel);
                    player.setExperiencePoints((int) (player.getNextLevelExperience() * expProgress));
                }
            });
        }

        // Performs a consuming function for every advancement entry registered on the server
        private static void forEachAdvancementEntry(
                @NotNull MinecraftServer server,
                //#if MC==12001
                //$$ @NotNull ThrowingConsumer<net.minecraft.advancement.Advancement> con
                //#else
                @NotNull ThrowingConsumer<net.minecraft.advancement.AdvancementEntry> con
                //#endif
        ) {
            server.getAdvancementLoader().getAdvancements().forEach(con);
        }

    }

    @Getter
    @Setter
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Location extends FabricData implements Data.Location, Adaptable {
        @SerializedName("x")
        private double x;
        @SerializedName("y")
        private double y;
        @SerializedName("z")
        private double z;
        @SerializedName("yaw")
        private float yaw;
        @SerializedName("pitch")
        private float pitch;
        @SerializedName("world")
        private World world;

        @NotNull
        public static FabricData.Location from(double x, double y, double z,
                                               float yaw, float pitch, @NotNull World world) {
            return new FabricData.Location(x, y, z, yaw, pitch, world);
        }

        @NotNull
        public static FabricData.Location adapt(@NotNull ServerPlayerEntity player) {
            //#if MC==12001
            //$$ final String worldName = player.getWorld().getDimensionKey().getValue().toString();
            //#else
            final String worldName = player.getWorld().getDimensionEntry().getIdAsString();
            //#endif
            return from(
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    player.getYaw(),
                    player.getPitch(),
                    new World(
                            Objects.requireNonNull(
                                    player.getWorld(), "World is null"
                            ).getRegistryKey().getValue().toString(),
                            UUID.nameUUIDFromBytes(worldName.getBytes()),
                            worldName
                    )
            );
        }

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
            final ServerPlayerEntity player = user.getPlayer();
            final MinecraftServer server = plugin.getMinecraftServer();

            // Find world
            final String worldName = world.name();
            final ServerWorld target = server.getWorld(server.getWorldRegistryKeys().stream()
                    .filter(key -> key.getValue().equals(Identifier.tryParse(worldName))).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Invalid target world: %s".formatted(worldName))));

            // Apply teleport
            try {
                player.dismountVehicle();
                //#if MC>=12104
                player.teleport(target, x, y, z, Set.of(), yaw, pitch, true);
                //#else
                //$$ player.teleport(target, x, y, z, yaw, pitch);
                //#endif
            } catch (Throwable e) {
                throw new IllegalStateException("Failed to apply location", e);
            }
        }

    }

    @Getter
    @Setter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class CardinalComponents extends FabricData implements Adaptable {

        // NBT key under which Cardinal Components stores an entity's components.
        // Stable across CCA 2.x->5.x. Do not change without verifying (see debug step).
        private static final String CCA_NBT_KEY = "cardinal_components";

        // Custom identifier (namespace != "husksync" => isCustom() == true => enabled).
        // Applied AFTER attributes & health (optional deps) so HuskSync's attribute/health
        // sync does not overwrite the modifiers set by Origins powers (cf. the recent fix
        // "health/maxhealth gone after server switch").
        public static final net.william278.husksync.data.Identifier IDENTIFIER =
                net.william278.husksync.data.Identifier.from(
                        "husksync_cca", "cardinal_components",
                        Set.of(
                                net.william278.husksync.data.Identifier.Dependency.optional(
                                        net.kyori.adventure.key.Key.key("husksync", "attributes")),
                                net.william278.husksync.data.Identifier.Dependency.optional(
                                        net.kyori.adventure.key.Key.key("husksync", "health"))
                        )
                );

        // Components NBT serialized as SNBT (text). Robust and multi-version; avoids any
        // dependency on NbtIo whose signature changes across MC versions.
        @SerializedName("components")
        private String components;

        @NotNull
        public static FabricData.CardinalComponents from(@NotNull String components) {
            return new FabricData.CardinalComponents(components);
        }

        /**
         * CAPTURE: reads the player's live state. We serialize the whole entity NBT then
         * extract the CCA sub-tag. No dependency on the CCA API here (pure vanilla NBT).
         */
        @NotNull
        public static FabricData.CardinalComponents adapt(@NotNull ServerPlayerEntity player,
                                                          @NotNull FabricHuskSync plugin) {
            final net.minecraft.nbt.NbtCompound root = new net.minecraft.nbt.NbtCompound();
            //#if MC==12001
            //$$ player.writeNbt(root);
            //$$ final net.minecraft.nbt.NbtCompound cca = root.getCompound(CCA_NBT_KEY);
            //#else
            final net.minecraft.nbt.NbtCompound cca = new net.minecraft.nbt.NbtCompound();
            //#endif
            if (cca.isEmpty()) {
                plugin.debug("[CCA] No '" + CCA_NBT_KEY + "' tag found for "
                        + player.getGameProfile().getName() + " (keys: " + root.getKeys() + ")");
            }
            return from(cca.toString()); // SNBT
        }

        /**
         * APPLICATION: restores the CCA sub-tag then re-syncs to the client.
         */
        // Kill switch to skip restoring Cardinal Components data. Diagnostic / opt-out for when
        // CCA's own client sync misbehaves. Enabled by EITHER the JVM property
        // -Dhusksync.cca.disableApply=true OR by creating an empty file named
        // "husksync-disable-cca.flag" in the server's working directory (easier on hosts where
        // JVM flags can't be set). Checked each apply (rare: only on a sync).
        private static final boolean APPLY_DISABLED_PROP = Boolean.getBoolean("husksync.cca.disableApply");

        private static boolean isApplyDisabled() {
            if (APPLY_DISABLED_PROP) {
                return true;
            }
            try {
                return java.nio.file.Files.exists(java.nio.file.Path.of("husksync-disable-cca.flag"));
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) {
            if (isApplyDisabled()) {
                return;
            }
            final ServerPlayerEntity player = user.getPlayer();
            if (components == null || components.isBlank() || components.equals("{}")) {
                return;
            }
            // STRATEGY: load each CCA component individually via readFromNbt(), deferred
            // to the NEXT server tick. Two reasons for deferring:
            //
            // 1. Some CCA component readFromNbt() implementations internally call sync()
            //    which sends CustomPayloadS2CPacket. During the join sequence (especially
            //    Velocity server switches) the Netty pipeline may not be ready yet, causing
            //    the PacketByteBuf to be released prematurely → refCnt: 0 → player kicked.
            //
            // 2. By deferring to the next tick, the player's network handler is fully
            //    established, so any sync packets triggered by readFromNbt() can be
            //    encoded and sent without buffer lifecycle issues.
            //
            // We use CompletableFuture.runAsync() to hop to a background thread, then
            // srv.execute() to queue back onto the server thread for the NEXT tick.
            // (Calling srv.execute() from the server thread runs IMMEDIATELY due to
            // ReentrantThreadExecutor short-circuiting.)
            //#if MC==12001
            //$$ final net.minecraft.server.MinecraftServer srv = player.getServer();
            //$$ if (srv == null) return;
            //$$ final String componentsSafe = components;
            //$$ java.util.concurrent.CompletableFuture.runAsync(() ->
            //$$     srv.execute(() -> {
            //$$         try {
            //$$             final net.minecraft.nbt.NbtCompound cca =
            //$$                     net.minecraft.nbt.StringNbtReader.parse(componentsSafe);
            //$$             if (cca.isEmpty()) return;
            //$$             final Object container = player.getClass()
            //$$                     .getMethod("getComponentContainer").invoke(player);
            //$$             final Iterable<?> keys = (Iterable<?>) container.getClass()
            //$$                     .getMethod("keys").invoke(container);
            //$$             for (Object key : keys) {
            //$$                 try {
            //$$                     final net.minecraft.util.Identifier id =
            //$$                             (net.minecraft.util.Identifier) key.getClass()
            //$$                                     .getMethod("getId").invoke(key);
            //$$                     final String idStr = id.toString();
            //$$                     if (!cca.contains(idStr)) continue;
            //$$                     final net.minecraft.nbt.NbtCompound compNbt = cca.getCompound(idStr);
            //$$                     if (compNbt.isEmpty()) continue;
            //$$                     Object component = null;
            //$$                     for (java.lang.reflect.Method m : key.getClass().getMethods()) {
            //$$                         if ("get".equals(m.getName()) && m.getParameterCount() == 1) {
            //$$                             try { component = m.invoke(key, player); break; }
            //$$                             catch (Throwable ignored) {}
            //$$                         }
            //$$                     }
            //$$                     if (component == null) continue;
            //$$                     for (java.lang.reflect.Method m : component.getClass().getMethods()) {
            //$$                         if ("readFromNbt".equals(m.getName())
            //$$                                 && m.getParameterCount() == 1
            //$$                                 && m.getParameterTypes()[0].isAssignableFrom(
            //$$                                         net.minecraft.nbt.NbtCompound.class)) {
            //$$                             m.invoke(component, compNbt);
            //$$                             plugin.debug("[CCA] Restored component: " + idStr);
            //$$                             break;
            //$$                         }
            //$$                     }
            //$$                 } catch (Throwable e) {
            //$$                     plugin.debug("[CCA] Failed to restore component " + key, e);
            //$$                 }
            //$$             }
            //$$         } catch (Throwable e) {
            //$$             plugin.log(java.util.logging.Level.WARNING,
            //$$                     "[CCA] Failed to apply cardinal_components to "
            //$$                             + player.getGameProfile().getName(), e);
            //$$         }
            //$$     })
            //$$ );
            //#endif
        }

        // Forces a server -> client re-sync of every Cardinal component attached to the
        // player after the NBT restoration (Trinkets, Origins, ...). Done via reflection so
        // there is no compile-time dependency on the CCA API: it only runs at runtime where
        // CCA is present, and degrades silently (data stays restored server-side) otherwise.
        private static void resync(@NotNull ServerPlayerEntity player) {
            final net.minecraft.server.MinecraftServer server = player.getServer();
            if (server == null) {
                return;
            }
            // Re-send the components on the MAIN server thread, next tick. Sending packets off
            // the server thread, or mid-way through the join/sync sequence before the player's
            // network handler is ready, can cause a CustomPayloadS2CPacket buffer to be released
            // early (Netty "IllegalReferenceCountException: refCnt: 0" -> client kicked with an
            // Internal Exception). Deferring to the main thread sends them in a clean context.
            server.execute(() -> {
                try {
                    final Object container = player.getClass()
                            .getMethod("getComponentContainer").invoke(player);
                    final Object keys = container.getClass().getMethod("keys").invoke(container);
                    for (Object key : (java.util.Set<?>) keys) {
                        try {
                            key.getClass().getMethod("sync", Object.class).invoke(key, player);
                        } catch (Throwable ignored) {
                            // component not syncable: ignore
                        }
                    }
                } catch (Throwable ignored) {
                    // CCA absent or API differs: data already restored server-side,
                    // the client refreshes on the next relog.
                }
            });
        }

    }

    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Statistics extends FabricData implements Data.Statistics, Adaptable {

        private static final String BLOCK_STAT_TYPE = "block";
        private static final String ITEM_STAT_TYPE = "item";
        private static final String ENTITY_STAT_TYPE = "entity_type";

        @SerializedName("generic")
        private Map<String, Integer> genericStatistics;
        @SerializedName("blocks")
        private Map<String, Map<String, Integer>> blockStatistics;
        @SerializedName("items")
        private Map<String, Map<String, Integer>> itemStatistics;
        @SerializedName("entities")
        private Map<String, Map<String, Integer>> entityStatistics;

        @NotNull
        public static FabricData.Statistics adapt(@NotNull ServerPlayerEntity player) throws IllegalStateException {
            // Adapt typed stats
            final Map<String, Map<String, Integer>> blocks = Maps.newHashMap(),
                    items = Maps.newHashMap(), entities = Maps.newHashMap();
            Registries.STAT_TYPE.getEntrySet().forEach(stat -> {
                // This is necessary to prevent weird re-mappings with Registry#getKey()
                //#if MC>0
                //$$ final Registry<?> registry = stat.getValue().getRegistry();
                //$$ final String registryId = registry.getKey().getValue().value();
                //$$ if (registryId.equals("custom_stat")) {
                //$$    return;
                //$$ }
                //#else
                final Registry<?> registry = stat.getValue().getRegistry();
                final String registryId = registry.getKey().getValue().value();
                if (registryId.equals("custom_stat")) {
                    return;
                }
                //#endif

                final Map<String, Integer> map = (switch (registryId) {
                    case BLOCK_STAT_TYPE -> blocks;
                    case ITEM_STAT_TYPE -> items;
                    case ENTITY_STAT_TYPE -> entities;
                    default -> throw new IllegalStateException("Unexpected value: %s".formatted(registryId));
                }).compute(stat.getKey().getValue().toString(), (k, v) -> v == null ? Maps.newHashMap() : v);

                registry.getEntrySet().forEach(entry -> {
                    @SuppressWarnings({"unchecked", "rawtypes"}) final int value = player.getStatHandler()
                            .getStat((StatType) stat.getValue(), entry.getValue());
                    if (value != 0) {
                        map.put(entry.getKey().getValue().toString(), value);
                    }
                });
            });

            // Add generic stats
            final Map<String, Integer> generic = Maps.newHashMap();
            Registries.CUSTOM_STAT.getEntrySet().forEach(stat -> {
                final int value = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(stat.getValue()));
                if (value != 0) {
                    generic.put(stat.getKey().getValue().toString(), value);
                }
            });

            return new FabricData.Statistics(generic, blocks, items, entities);
        }

        @NotNull
        public static FabricData.Statistics from(@NotNull Map<String, Integer> generic,
                                                 @NotNull Map<String, Map<String, Integer>> blocks,
                                                 @NotNull Map<String, Map<String, Integer>> items,
                                                 @NotNull Map<String, Map<String, Integer>> entities) {
            return new FabricData.Statistics(generic, blocks, items, entities);
        }

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) {
            final ServerPlayerEntity player = user.getPlayer();
            genericStatistics.forEach((id, v) -> applyStat(player, id, null, v));
            blockStatistics.forEach((id, m) -> m.forEach((b, v) -> applyStat(player, id, BLOCK_STAT_TYPE, v, b)));
            itemStatistics.forEach((id, m) -> m.forEach((i, v) -> applyStat(player, id, ITEM_STAT_TYPE, v, i)));
            entityStatistics.forEach((id, m) -> m.forEach((e, v) -> applyStat(player, id, ENTITY_STAT_TYPE, v, e)));
            player.getStatHandler().updateStatSet();
            player.getStatHandler().sendStats(player);
        }

        @SuppressWarnings("unchecked")
        private <T> void applyStat(@NotNull ServerPlayerEntity player, @NotNull String id,
                                   @Nullable String type, int value, @NotNull String... key) {
            final Identifier statId = Identifier.tryParse(id);
            if (statId == null) {
                return;
            }
            if (type == null) {
                player.getStatHandler().setStat(
                        player,
                        Stats.CUSTOM.getOrCreateStat(Registries.CUSTOM_STAT.get(statId)),
                        value
                );
                return;
            }
            final Identifier typeId = Identifier.tryParse(type);
            final StatType<T> statType = (StatType<T>) Registries.STAT_TYPE.get(typeId);
            if (statType == null) {
                return;
            }

            final Registry<T> typeReg = statType.getRegistry();
            final T typeInstance = typeReg.get(Identifier.tryParse(key[0]));
            if (typeInstance == null) {
                return;
            }

            player.getStatHandler().setStat(player, statType.getOrCreateStat(typeInstance), value);
        }

    }

    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Attributes extends FabricData implements Data.Attributes, Adaptable {

        private List<Attribute> attributes;

        @NotNull
        public static FabricData.Attributes adapt(@NotNull ServerPlayerEntity player, @NotNull HuskSync plugin) {
            final List<Attribute> attributes = Lists.newArrayList();
            final AttributeSettings settings = plugin.getSettings().getSynchronization().getAttributes();
            Registries.ATTRIBUTE.forEach(id -> {
                //#if MC==12001
                //$$ final EntityAttributeInstance instance = player.getAttributeInstance(id);
                //$$ final Identifier key = Registries.ATTRIBUTE.getId(id);
                //$$ if (instance == null || key == null || settings.isIgnoredAttribute(key.asString())) {
                //$$     return;
                //$$ }
                //$$ final Set<Modifier> modifiers = Sets.newHashSet();
                //$$ instance.getModifiers().forEach(modifier -> modifiers.add(new Modifier(
                //$$         modifier.getId(),
                //$$         modifier.getName(),
                //$$         modifier.getValue(),
                //$$         modifier.getOperation().getId(),
                //$$         -1
                //$$ )));
                //#else
                final EntityAttributeInstance instance = player.getAttributeInstance(RegistryEntry.of(id));
                final Identifier key = Registries.ATTRIBUTE.getId(id);
                if (instance == null || key == null || settings.isIgnoredAttribute(key.asString())) {
                    return;
                }
                final Set<Modifier> modifiers = Sets.newHashSet();
                instance.getModifiers().forEach(modifier -> modifiers.add(new Modifier(
                        modifier.id().toString(),
                        modifier.value(),
                        modifier.operation().getId(),
                        Modifier.ANY_EQUIPMENT_SLOT_GROUP
                )));
                //#endif
                attributes.add(new Attribute(
                        key.toString(),
                        instance.getBaseValue(),
                        modifiers
                ));
            });
            return new FabricData.Attributes(attributes);
        }

        public Optional<Attribute> getAttribute(@NotNull EntityAttribute id) {
            return Optional.ofNullable(Registries.ATTRIBUTE.getId(id)).map(Identifier::toString)
                    .flatMap(key -> attributes.stream().filter(attribute -> attribute.name().equals(key)).findFirst());
        }

        @SuppressWarnings("unused")
        public Optional<Attribute> getAttribute(@NotNull String key) {
            final EntityAttribute attribute = matchAttribute(key);
            if (attribute == null) {
                return Optional.empty();
            }
            return getAttribute(attribute);
        }

        @Override
        protected void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) {
            final AttributeSettings settings = plugin.getSettings().getSynchronization().getAttributes();
            Registries.ATTRIBUTE.forEach(id -> {
                final Identifier key = Registries.ATTRIBUTE.getId(id);
                if (key == null || settings.isIgnoredAttribute(key.toString())) {
                    return;
                }
                applyAttribute(
                        //#if MC==12001
                        //$$ user.getPlayer().getAttributeInstance(id),
                        //#else
                        user.getPlayer().getAttributeInstance(RegistryEntry.of(id)),
                        //#endif
                        getAttribute(id).orElse(null)
                );
            });

        }

        private static void applyAttribute(@Nullable EntityAttributeInstance instance,
                                           @Nullable Attribute attribute) {
            if (instance == null) {
                return;
            }
            instance.getModifiers().forEach(instance::removeModifier);
            instance.setBaseValue(attribute == null ? instance.getValue() : attribute.baseValue());
            if (attribute != null) {
                //#if MC==12001
                //$$ attribute.modifiers().forEach(modifier -> instance.addPersistentModifier(new EntityAttributeModifier(
                //$$         modifier.uuid(),
                //$$         modifier.name(),
                //$$         modifier.amount(),
                //$$         EntityAttributeModifier.Operation.fromId(modifier.operation())
                //$$ )));
                //#else
                attribute.modifiers().forEach(modifier -> instance.addTemporaryModifier(new EntityAttributeModifier(
                        Identifier.of(modifier.name()),
                        modifier.amount(),
                        EntityAttributeModifier.Operation.ID_TO_VALUE.apply(modifier.operation())
                )));
                //#endif
            }
        }

    }

    @Getter
    @Setter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Health extends FabricData implements Data.Health, Adaptable {
        @SerializedName("health")
        private double health;
        @SerializedName("health_scale")
        private double healthScale;
        @SerializedName("is_health_scaled")
        private boolean isHealthScaled;


        @NotNull
        public static FabricData.Health from(double health, double scale, boolean isScaled) {
            return new FabricData.Health(health, scale, isScaled);
        }

        @NotNull
        public static FabricData.Health adapt(@NotNull ServerPlayerEntity player) {
            return from(
                    player.getHealth(),
                    20.0f, false // Health scale is a Bukkit API feature, not used in Fabric
            );
        }

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
            final ServerPlayerEntity player = user.getPlayer();
            player.setHealth((float) health);
        }

    }


    @Getter
    @Setter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Hunger extends FabricData implements Data.Hunger, Adaptable {

        @SerializedName("food_level")
        private int foodLevel;
        @SerializedName("saturation")
        private float saturation;
        @SerializedName("exhaustion")
        private float exhaustion;

        @NotNull
        public static FabricData.Hunger adapt(@NotNull ServerPlayerEntity player) {
            final HungerManager hunger = player.getHungerManager();
            //#if MC>=12104
            float exhaustion = ((HungerManagerMixin) hunger).getExhaustion();
            //#else
            //$$ float exhaustion = hunger.getExhaustion();
            //#endif
            return from(hunger.getFoodLevel(), hunger.getSaturationLevel(), exhaustion);
        }

        @NotNull
        public static FabricData.Hunger from(int foodLevel, float saturation, float exhaustion) {
            return new FabricData.Hunger(foodLevel, saturation, exhaustion);
        }

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
            final ServerPlayerEntity player = user.getPlayer();
            final HungerManager hunger = player.getHungerManager();
            hunger.setFoodLevel(foodLevel);
            hunger.setSaturationLevel(saturation);
            //#if MC>=12104
            ((HungerManagerMixin) hunger).setExhaustion(exhaustion);
            //#else
            //$$ hunger.setExhaustion(exhaustion);
            //#endif
        }

    }

    @Getter
    @Setter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Experience extends FabricData implements Data.Experience, Adaptable {

        @SerializedName("total_experience")
        private int totalExperience;

        @SerializedName("exp_level")
        private int expLevel;

        @SerializedName("exp_progress")
        private float expProgress;

        @NotNull
        public static FabricData.Experience from(int totalExperience, int expLevel, float expProgress) {
            return new FabricData.Experience(totalExperience, expLevel, expProgress);
        }

        @NotNull
        public static FabricData.Experience adapt(@NotNull ServerPlayerEntity player) {
            return from(player.totalExperience, player.experienceLevel, player.experienceProgress);
        }

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
            final ServerPlayerEntity player = user.getPlayer();
            player.totalExperience = totalExperience;
            player.setExperienceLevel(expLevel);
            player.setExperiencePoints((int) (player.getNextLevelExperience() * expProgress));
        }

    }

    @Getter
    @Setter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class GameMode extends FabricData implements Data.GameMode, Adaptable {

        @SerializedName("game_mode")
        private String gameMode;

        @NotNull
        public static FabricData.GameMode from(@NotNull String gameMode) {
            return new FabricData.GameMode(gameMode);
        }

        @NotNull
        public static FabricData.GameMode adapt(@NotNull ServerPlayerEntity player) {
            return from(player.interactionManager.getGameMode().asString());
        }

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
            //#if MC<12105
            //$$ user.getPlayer().changeGameMode(net.minecraft.world.GameMode.byName(gameMode));
            //#else
            user.getPlayer().changeGameMode(net.minecraft.world.GameMode.byId(gameMode));
            //#endif
        }

    }

    @Getter
    @Setter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class FlightStatus extends FabricData implements Data.FlightStatus, Adaptable {

        @SerializedName("allow_flight")
        private boolean allowFlight;
        @SerializedName("is_flying")
        private boolean flying;

        @NotNull
        public static FabricData.FlightStatus from(boolean allowFlight, boolean flying) {
            return new FabricData.FlightStatus(allowFlight, allowFlight && flying);
        }

        @NotNull
        public static FabricData.FlightStatus adapt(@NotNull ServerPlayerEntity player) {
            return from(player.getAbilities().allowFlying, player.getAbilities().flying);
        }

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
            final ServerPlayerEntity player = user.getPlayer();
            player.getAbilities().allowFlying = allowFlight;
            player.getAbilities().flying = allowFlight && flying;
            player.sendAbilitiesUpdate();
        }

    }

    /**
     * Synchronises the contents of every Sophisticated Backpack carried by the player.
     *
     * <p>Sophisticated Backpacks stores item contents in a world-level SavedData file
     * ({@code world/data/sophisticatedbackpacks.dat}) keyed by a UUID written into the
     * item's NBT tag ({@code storage_uuid}).  HuskSync's normal inventory sync therefore
     * only transfers the UUID reference; the actual slots live on the source server's disk.
     *
     * <p>This handler solves the problem by:
     * <ol>
     *   <li><b>Capture</b> – scanning the player's inventory for SB items, reading their
     *       contents from {@code BackpackStorage} via reflection and serialising each
     *       {@code NbtCompound} as SNBT in the snapshot.</li>
     *   <li><b>Apply</b> – writing every (UUID → NbtCompound) pair back into the target
     *       server's {@code BackpackStorage}, so the UUID already on the synced item
     *       resolves correctly.</li>
     * </ol>
     *
     * <p>All {@code BackpackStorage} calls go through reflection so that there is
     * <em>zero compile-time dependency</em> on Sophisticated Backpacks.  The class
     * degrades silently if the mod is absent.
     *
     * <p>Only active on MC 1.20.1 (NBT item tags); other versions compile to no-ops.
     */
    @Getter
    @Setter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class SophisticatedBackpacks extends FabricData implements Adaptable {

        /** NBT key inside the item tag that holds the backpack's storage UUID.
         *  Verified from live NBT dump: SB Fabric 1.20.1 uses "contentsUuid" (IntArray). */
        private static final String NBT_STORAGE_UUID_KEY = "contentsUuid";

        /** NBT key inside the contents compound that holds the inventory list. */
        private static final String NBT_INVENTORY_KEY = "inventory";

        /** NBT key inside the inventory compound that holds the item list. */
        private static final String NBT_ITEMS_KEY = "Items";

        /** Fully-qualified class name of BackpackStorage (resolved at runtime). */
        private static final String STORAGE_CLASS =
                "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage";

        /**
         * Custom identifier – namespace != "husksync" so it is treated as custom data
         * (always enabled when registered).  Applied AFTER inventory so the UUID already
         * sits on the restored item before we write the contents into BackpackStorage.
         */
        public static final net.william278.husksync.data.Identifier IDENTIFIER =
                net.william278.husksync.data.Identifier.from(
                        "husksync_sb", "sophisticated_backpacks",
                        Set.of(
                                net.william278.husksync.data.Identifier.Dependency.optional(
                                        net.kyori.adventure.key.Key.key("husksync", "inventory"))
                        )
                );

        /** Map of {@code UUID.toString()} → SNBT string of the backpack contents. */
        @SerializedName("backpacks")
        private Map<String, String> backpacks;

        // ── Capture ──────────────────────────────────────────────────────────────────

        @NotNull
        public static FabricData.SophisticatedBackpacks adapt(
                @NotNull ServerPlayerEntity player,
                @NotNull FabricHuskSync plugin) {
            final Map<String, String> result = new java.util.LinkedHashMap<>();
            //#if MC==12001
            //$$ try {
            //$$     final Object storage = resolveStorage(player.getServer(), plugin);
            //$$     if (storage == null) {
            //$$         return new SophisticatedBackpacks(result);
            //$$     }
            //$$     final net.minecraft.entity.player.PlayerInventory inv = player.getInventory();
            //$$     for (int i = 0; i < inv.size(); i++) {
            //$$         collectFromStack(inv.getStack(i), storage, result, plugin);
            //$$     }
            //$$ } catch (Throwable e) {
            //$$     plugin.log(java.util.logging.Level.WARNING,
            //$$             "[SB] Failed to capture backpack data for "
            //$$                     + player.getGameProfile().getName(), e);
            //$$ }
            //#endif
            return new SophisticatedBackpacks(result);
        }

        //#if MC==12001
        //$$ /**
        //$$  * Reads the backpack contents for one item stack (if it is a SB item) and adds
        //$$  * them to {@code result}.  Recurses into nested backpacks.
        //$$ */
        //$$ private static void collectFromStack(
        //$$         @NotNull net.minecraft.item.ItemStack stack,
        //$$         @NotNull Object storage,
        //$$         @NotNull Map<String, String> result,
        //$$         @NotNull FabricHuskSync plugin) {
        //$$     if (stack.isEmpty()) return;
        //$$     final net.minecraft.nbt.NbtCompound nbt = stack.getNbt();
        //$$     if (nbt == null) return;
        //$$     // SB stores the UUID as an IntArray [I;a,b,c,d] under "contentsUuid".
        //$$     // Use containsUuid() first; fall back to manual IntArray extraction.
        //$$     UUID uuid = null;
        //$$     if (nbt.containsUuid(NBT_STORAGE_UUID_KEY)) {
        //$$         uuid = nbt.getUuid(NBT_STORAGE_UUID_KEY);
        //$$     } else if (nbt.contains(NBT_STORAGE_UUID_KEY,
        //$$             net.minecraft.nbt.NbtElement.INT_ARRAY_TYPE)) {
        //$$         // Manual fallback: IntArray of 4 ints → UUID via NbtHelper
        //$$         try {
        //$$             uuid = net.minecraft.nbt.NbtHelper.toUuid(
        //$$                     nbt.get(NBT_STORAGE_UUID_KEY));
        //$$         } catch (Throwable ignored) {}
        //$$     }
        //$$     if (uuid == null) return;
        //$$     if (result.containsKey(uuid.toString())) return; // already collected
        //$$     try {
        //$$         final net.minecraft.nbt.NbtCompound contents = readContents(storage, uuid);
        //$$         if (contents == null || contents.isEmpty()) return;
        //$$         // Recurse into items nested inside this backpack before storing it
        //$$         final net.minecraft.nbt.NbtCompound invTag =
        //$$                 contents.getCompound(NBT_INVENTORY_KEY);
        //$$         final net.minecraft.nbt.NbtList items = invTag.getList(
        //$$                 NBT_ITEMS_KEY, net.minecraft.nbt.NbtElement.COMPOUND_TYPE);
        //$$         for (int i = 0; i < items.size(); i++) {
        //$$             try {
        //$$                 collectFromStack(
        //$$                         net.minecraft.item.ItemStack.fromNbt(items.getCompound(i)),
        //$$                         storage, result, plugin);
        //$$             } catch (Throwable ignored) {}
        //$$         }
        //$$         result.put(uuid.toString(), contents.toString()); // SNBT
        //$$     } catch (Throwable e) {
        //$$         plugin.debug("[SB] Failed to read contents for UUID " + uuid, e);
        //$$     }
        //$$ }
        //$$
        //$$ /**
        //$$  * Reads the contents compound for a given UUID.
        //$$  * Prefers {@code getBackpackContents(UUID)} (returns Optional, read-only) and
        //$$  * falls back to {@code getOrCreateBackpackContents(UUID)} if that method does
        //$$  * not exist in this build of the mod.
        //$$ */
        //$$ private static volatile java.lang.reflect.Method cachedReadMethod;
        //$$ private static volatile boolean readMethodResolved = false;
        //$$
        //$$ @Nullable
        //$$ private static net.minecraft.nbt.NbtCompound readContents(
        //$$         @NotNull Object storage, @NotNull UUID uuid) throws Throwable {
        //$$     if (!readMethodResolved) {
        //$$         readMethodResolved = true;
        //$$         try {
        //$$             cachedReadMethod = storage.getClass()
        //$$                     .getMethod("getBackpackContents", UUID.class);
        //$$         } catch (NoSuchMethodException ignored) {
        //$$             try {
        //$$                 cachedReadMethod = storage.getClass()
        //$$                         .getMethod("getOrCreateBackpackContents", UUID.class);
        //$$             } catch (NoSuchMethodException ignored2) {}
        //$$         }
        //$$     }
        //$$     if (cachedReadMethod == null) return null;
        //$$     final Object result = cachedReadMethod.invoke(storage, uuid);
        //$$     if (result instanceof java.util.Optional<?> opt) {
        //$$         return opt.isPresent() ? (net.minecraft.nbt.NbtCompound) opt.get() : null;
        //$$     }
        //$$     return (net.minecraft.nbt.NbtCompound) result;
        //$$ }
        //#endif

        // ── Apply ─────────────────────────────────────────────────────────────────────

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) {
            if (backpacks == null || backpacks.isEmpty()) return;
            final ServerPlayerEntity player = user.getPlayer();
            final MinecraftServer server = player.getServer();
            if (server == null) return;
            // Write contents SYNCHRONOUSLY on the current (main server) thread.
            //
            // DO NOT defer via server.execute() here. HuskSync already calls apply() on
            // the main server thread. Deferring to the next tick would let SB's own
            // inventory-sync code access the backpack UUID first, find no entry in
            // BackpackStorage, create an empty cache entry, and then our write would be
            // silently shadowed by that cached-empty version – causing the "contents flash
            // then disappear" symptom. Writing synchronously ensures the contents are in
            // BackpackStorage before Minecraft sends the inventory packet to the client.
            try {
                final Object storage = resolveStorage(server, plugin);
                if (storage == null) {
                    plugin.log(java.util.logging.Level.WARNING,
                            "[SB] BackpackStorage unavailable – cannot restore backpack "
                                    + "contents for " + player.getGameProfile().getName());
                    return;
                }
                // setBackpackContents(UUID, NbtCompound) does not exist in this port.
                // Instead we use getOrCreateBackpackContents(UUID) which returns a mutable
                // reference to the stored NbtCompound, then replace its content in-place
                // and call markDirty() so the change is persisted.
                final java.lang.reflect.Method getOrCreate =
                        findMethod(storage.getClass(), "getOrCreateBackpackContents", UUID.class);
                if (getOrCreate == null) {
                    plugin.log(java.util.logging.Level.WARNING,
                            "[SB] getOrCreateBackpackContents not found on "
                                    + storage.getClass().getName());
                    return;
                }
                for (Map.Entry<String, String> entry : backpacks.entrySet()) {
                    try {
                        final UUID uuid = UUID.fromString(entry.getKey());
                        //#if MC==12001
                        //$$ final net.minecraft.nbt.NbtCompound src =
                        //$$     net.minecraft.nbt.StringNbtReader.parse(entry.getValue());
                        //$$ final net.minecraft.nbt.NbtCompound dest =
                        //$$     (net.minecraft.nbt.NbtCompound) getOrCreate.invoke(storage, uuid);
                        //$$ // Clear existing content and copy ours in-place
                        //$$ for (String k : new java.util.HashSet<>(dest.getKeys())) {
                        //$$     dest.remove(k);
                        //$$ }
                        //$$ for (String k : src.getKeys()) {
                        //$$     dest.put(k, src.get(k).copy());
                        //$$ }
                        //$$ plugin.debug("[SB] Restored backpack UUID " + uuid);
                        //#endif
                    } catch (Throwable e) {
                        plugin.debug("[SB] Failed to restore UUID " + entry.getKey(), e);
                    }
                }
                // markDirty() is inherited from PersistentState – always present
                try {
                    storage.getClass().getMethod("markDirty").invoke(storage);
                } catch (Throwable ignored) {}
            } catch (Throwable e) {
                plugin.log(java.util.logging.Level.WARNING,
                        "[SB] Failed to apply backpack data for "
                                + player.getGameProfile().getName(), e);
            }
        }

        // ── Reflection helpers ────────────────────────────────────────────────────────

        /**
         * Resolves the live {@code BackpackStorage} instance via reflection.
         * Tries three call signatures in order:
         * <ol>
         *   <li>{@code BackpackStorage.get(MinecraftServer)}</li>
         *   <li>{@code BackpackStorage.get(ServerWorld)} (passes overworld)</li>
         *   <li>{@code BackpackStorage.get()} (no-arg singleton)</li>
         * </ol>
         * Logs diagnostics (class not found, available methods) to help identify
         * naming differences across mod versions.
         */
        /** Cached reflection accessor for BackpackStorage.get(). Resolved once. */
        private static volatile java.lang.reflect.Method cachedGetMethod;
        private static volatile boolean storageResolved = false;

        @Nullable
        private static Object resolveStorage(
                @Nullable MinecraftServer server,
                @Nullable FabricHuskSync plugin) {
            if (server == null) return null;
            // Fast path: use cached method after first resolution
            if (storageResolved) {
                if (cachedGetMethod == null) return null;
                try {
                    if (cachedGetMethod.getParameterCount() == 0) {
                        return cachedGetMethod.invoke(null);
                    } else if (cachedGetMethod.getParameterTypes()[0] == MinecraftServer.class) {
                        return cachedGetMethod.invoke(null, server);
                    } else {
                        return cachedGetMethod.invoke(null, server.getOverworld());
                    }
                } catch (Throwable e) { return null; }
            }
            // First call: resolve via reflection and cache
            storageResolved = true;
            try {
                final Class<?> cls = Class.forName(STORAGE_CLASS);
                if (plugin != null) {
                    final StringBuilder methods = new StringBuilder();
                    for (java.lang.reflect.Method m : cls.getMethods()) {
                        if (m.getParameterCount() <= 1) {
                            methods.append(m.getName()).append('(');
                            for (Class<?> p : m.getParameterTypes()) {
                                methods.append(p.getSimpleName());
                            }
                            methods.append(") ");
                        }
                    }
                    plugin.debug("[SB] BackpackStorage class found. Methods: " + methods);
                }
                // Try signatures in order and cache the working one
                try {
                    cachedGetMethod = cls.getMethod("get", MinecraftServer.class);
                    return cachedGetMethod.invoke(null, server);
                } catch (NoSuchMethodException ignored) {}
                try {
                    cachedGetMethod = cls.getMethod("get",
                            net.minecraft.server.world.ServerWorld.class);
                    return cachedGetMethod.invoke(null, server.getOverworld());
                } catch (NoSuchMethodException ignored) {}
                try {
                    cachedGetMethod = cls.getMethod("get");
                    return cachedGetMethod.invoke(null);
                } catch (NoSuchMethodException ignored) {}
                if (plugin != null) {
                    plugin.log(java.util.logging.Level.WARNING,
                            "[SB] No matching get() method found on " + cls.getName());
                }
            } catch (ClassNotFoundException e) {
                if (plugin != null) {
                    plugin.log(java.util.logging.Level.WARNING,
                            "[SB] BackpackStorage class not found: " + STORAGE_CLASS
                                    + " – is the mod loaded?");
                }
            } catch (Throwable e) {
                if (plugin != null) {
                    plugin.log(java.util.logging.Level.WARNING,
                            "[SB] resolveStorage failed", e);
                }
            }
            return null;
        }

        /** Looks up a method by name + parameter types (public or declared). */
        @Nullable
        private static java.lang.reflect.Method findMethod(
                @NotNull Class<?> cls,
                @NotNull String name,
                @NotNull Class<?>... params) {
            try { return cls.getMethod(name, params); }
            catch (NoSuchMethodException ignored) {}
            try { return cls.getDeclaredMethod(name, params); }
            catch (NoSuchMethodException ignored) {}
            return null;
        }
    }

}
