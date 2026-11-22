package com.landclaims;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server limits, synced to clients so the UI can show them. */
public final class LandClaimsConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue MAX_CLAIMS_PER_TEAM = BUILDER
            .comment("How many chunks one team can claim")
            .defineInRange("maxClaimsPerTeam", 100, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue MAX_FORCE_LOADED_PER_TEAM = BUILDER
            .comment("How many of a team's claimed chunks can stay loaded while nobody is nearby")
            .defineInRange("maxForceLoadedPerTeam", 25, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.BooleanValue PROTECT_FROM_EXPLOSIONS = BUILDER
            .comment("Stop explosions from breaking blocks in claimed chunks")
            .define("protectFromExplosions", true);

    public static final ModConfigSpec.BooleanValue FORCE_LOAD_ONLY_WHILE_ONLINE = BUILDER
            .comment("Keep a team's chunks loaded only while at least one team member is online")
            .define("forceLoadOnlyWhileOnline", false);

    static final ModConfigSpec SPEC = BUILDER.build();

    private LandClaimsConfig() {
    }
}
