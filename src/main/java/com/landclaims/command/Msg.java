package com.landclaims.command;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Chat messages. Texts live in assets/landclaims/lang. */
public final class Msg {
    private Msg() {
    }

    public static MutableComponent info(String key, Object... args) {
        return Component.translatable("landclaims.msg." + key, args);
    }

    public static MutableComponent ok(String key, Object... args) {
        return info(key, args).withStyle(ChatFormatting.GREEN);
    }

    public static MutableComponent error(String key, Object... args) {
        return info(key, args).withStyle(ChatFormatting.RED);
    }
}
