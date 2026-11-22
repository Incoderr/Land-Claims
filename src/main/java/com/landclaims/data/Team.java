package com.landclaims.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/** A group of players that shares claims. The owner is always also a member. */
public final class Team {
    public static final Codec<Team> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Team::id),
            Codec.STRING.fieldOf("name").forGetter(Team::name),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(Team::owner),
            UUIDUtil.CODEC.listOf().fieldOf("members").forGetter(t -> new ArrayList<>(t.members))
    ).apply(i, Team::new));

    private final UUID id;
    private final String name;
    private UUID owner;
    private final Set<UUID> members;

    Team(UUID id, String name, UUID owner, List<UUID> members) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.members = new LinkedHashSet<>(members);
        this.members.add(owner);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public UUID owner() {
        return owner;
    }

    public boolean isOwner(UUID player) {
        return owner.equals(player);
    }

    public boolean isMember(UUID player) {
        return members.contains(player);
    }

    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    void addMember(UUID player) {
        members.add(player);
    }

    void removeMember(UUID player) {
        members.remove(player);
    }

    void setOwner(UUID player) {
        owner = player;
    }
}
