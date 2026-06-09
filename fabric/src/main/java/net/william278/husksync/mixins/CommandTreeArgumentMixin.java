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

package net.william278.husksync.mixins;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.argument.ArgumentTypes;
import net.minecraft.command.argument.serialize.ArgumentSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Fixes "Unrecognized argument type" kick when the command tree is sent to a joining client.
// HuskSync (via Uniform) registers commands whose argument types are not registered in the
// vanilla ArgumentTypes registry. When the server serializes the command tree
// (CommandTreeS2CPacket$ArgumentNode constructor), ArgumentTypes.getArgumentTypeProperties
// throws IllegalArgumentException for those types and the player can't be placed in the world.
// This mirrors Uniform's own ArgumentNodeMixin, which is never compiled for 1.20.1 upstream
// (its source is guarded //#if MC>=12108), so we provide it here. Falls back to a string
// argument's properties for unknown types.
@Mixin(targets = "net.minecraft.network.packet.s2c.play.CommandTreeS2CPacket$ArgumentNode")
public class CommandTreeArgumentMixin {

    @Redirect(
            method = "<init>(Lcom/mojang/brigadier/tree/ArgumentCommandNode;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/command/argument/ArgumentTypes;getArgumentTypeProperties(Lcom/mojang/brigadier/arguments/ArgumentType;)Lnet/minecraft/command/argument/serialize/ArgumentSerializer$ArgumentTypeProperties;"
            )
    )
    private static ArgumentSerializer.ArgumentTypeProperties<?> husksync$fallbackUnknownArgument(ArgumentType<?> type) {
        try {
            return ArgumentTypes.getArgumentTypeProperties(type);
        } catch (IllegalArgumentException e) {
            return ArgumentTypes.getArgumentTypeProperties(StringArgumentType.string());
        }
    }

}
