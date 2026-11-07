package com.gilfort.civmod.api;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.gilfort.civmod.settlement.DayClock;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * What a {@link ServiceEffect} gets: the act that is played, where, by whom, and the listeners within its radius.
 *
 * @param level     the level of the act
 * @param act       the act, with the numbers a datapack may have changed
 * @param center    the center of the act: the radius is measured from here
 * @param performer the settler that plays the act; it is not one of the listeners
 * @param skill     the performer's skill level in the Job
 * @param clock     the clock value of the moment the act is played
 * @param settlers  the settlers of the performer's settlement within the radius, empty if the effect is not for settlers
 * @param players   the players within the radius, empty if the effect is not for players
 */
@ApiStatus.Experimental
public record ServiceContext(ServerLevel level, ServiceAct act, Vec3 center, Mob performer, int skill, long clock, List<Mob> settlers,
        List<ServerPlayer> players) {

    /**
     * @return the number of the day the act is played on
     */
    public long day() {
        return DayClock.day(clock);
    }
}
