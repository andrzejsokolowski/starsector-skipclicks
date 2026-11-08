package skipclicks.rulecmd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.MutableStat.StatMod;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.procgen.SalvageEntityGenDataSpec;
import com.fs.starfarer.api.impl.campaign.procgen.SalvageEntityGenDataSpec.DropData;
import com.fs.starfarer.api.impl.campaign.procgen.themes.SalvageEntityGeneratorOld;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.SalvageEntity;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.BaseSalvageSpecial;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.BaseSalvageSpecial.ExtraSalvage;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin.DebrisFieldSource;
import com.fs.starfarer.api.util.Misc;

/**
 * Folds the debris field that a salvaged station, probe or cache leaves behind into its loot.
 *
 * Vanilla SalvageEntity.performSalvage shows the loot and then calls convertToDebrisField, which spawns a
 * PLAYER_SALVAGE debris field. FIELD_SALVAGE_FRACTION_PER_ATTEMPT is 1, so one scavenge of that field takes all
 * of it. prepare() works out that one scavenge before the salvage runs, with the same numbers vanilla would use
 * (the field's drops, seed, density and crew and machinery needs), and adds it to the target's extra salvage,
 * which performSalvage puts on the same loot screen. finish() then removes the field and grants its salvage XP.
 * When no field appeared (another mod's salvage step, or the salvage did not run yet) the extra loot is taken back.
 */
final class DebrisMerge {

    private static final Logger log = Global.getLogger(DebrisMerge.class);

    private final SectorEntityToken entity;
    private final LocationAPI location;
    private final CargoAPI loot;
    private final Set<CampaignTerrainAPI> fieldsBefore;

    private DebrisMerge(SectorEntityToken entity, CargoAPI loot, Set<CampaignTerrainAPI> fieldsBefore) {
        this.entity = entity;
        this.location = entity.getContainingLocation();
        this.loot = loot;
        this.fieldsBefore = fieldsBefore;
    }

    /** Null when the target leaves no debris field, or on any error (then vanilla runs untouched). */
    static DebrisMerge prepare(InteractionDialogAPI dialog) {
        try {
            SectorEntityToken entity = dialog.getInteractionTarget();
            if (entity == null || entity.getContainingLocation() == null) return null;
            if (Entities.DEBRIS_FIELD_SHARED.equals(entity.getCustomEntityType())) return null;

            SalvageEntityGenDataSpec spec = specFor(entity);
            if (spec == null || spec.hasTag(Tags.SALVAGE_ENTITY_NO_DEBRIS)) return null;

            CargoAPI loot = scavengeOfLeftoverField(entity, spec);

            Set<CampaignTerrainAPI> before = Collections.newSetFromMap(new IdentityHashMap<CampaignTerrainAPI, Boolean>());
            before.addAll(debrisFields(entity.getContainingLocation()));

            if (!loot.isEmpty()) {
                MemoryAPI memory = entity.getMemoryWithoutUpdate();
                ExtraSalvage extra = BaseSalvageSpecial.getExtraSalvage(memory);
                if (extra != null && extra.cargo != null) {
                    extra.cargo.addAll(loot);
                } else {
                    CargoAPI copy = Global.getFactory().createCargo(true);
                    copy.addAll(loot);
                    BaseSalvageSpecial.addExtraSalvage(copy, memory, -1f);
                }
            }
            return new DebrisMerge(entity, loot, before);
        } catch (Throwable t) {
            log.error("SkipClicks: could not work out the leftover debris field, salvaging as usual", t);
            return null;
        }
    }

    /** Call after the salvage step. Removes the field the salvage left, or takes the loot back if there is none. */
    void finish() {
        try {
            List<CampaignTerrainAPI> spawned = new ArrayList<>();
            for (CampaignTerrainAPI field : debrisFields(location)) {
                if (fieldsBefore.contains(field)) continue;
                DebrisFieldTerrainPlugin plugin = (DebrisFieldTerrainPlugin) field.getPlugin();
                if (plugin.getParams() == null || plugin.getParams().source != DebrisFieldSource.PLAYER_SALVAGE) continue;
                spawned.add(field);
            }

            if (spawned.isEmpty()) {
                takeLootBack();
                return;
            }

            long xp = 0;
            for (CampaignTerrainAPI field : spawned) {
                DebrisFieldTerrainPlugin plugin = (DebrisFieldTerrainPlugin) field.getPlugin();
                // What ScavengeAbility puts on the field's salvage spec.
                xp += (long) (plugin.getParams().baseSalvageXP * plugin.getParams().density);
                location.removeEntity(field);
            }
            if (xp > 0) Global.getSector().getPlayerPerson().getStats().addXP(xp);
        } catch (Throwable t) {
            log.error("SkipClicks: could not remove the leftover debris field", t);
        }
    }

    private void takeLootBack() {
        if (loot.isEmpty()) return;
        ExtraSalvage extra = BaseSalvageSpecial.getExtraSalvage(entity.getMemoryWithoutUpdate());
        if (extra == null || extra.cargo == null) return;
        extra.cargo.removeAll(loot);
        if (extra.cargo.isEmpty()) entity.getMemoryWithoutUpdate().unset(BaseSalvageSpecial.EXTRA_SALVAGE);
        log.info("SkipClicks: no debris field appeared after salvaging " + entity.getName() + ", leftover loot not added");
    }

    private static List<CampaignTerrainAPI> debrisFields(LocationAPI location) {
        List<CampaignTerrainAPI> result = new ArrayList<>();
        for (CampaignTerrainAPI terrain : location.getTerrainCopy()) {
            if (terrain.getPlugin() instanceof DebrisFieldTerrainPlugin) result.add(terrain);
        }
        return result;
    }

    private static SalvageEntityGenDataSpec specFor(SectorEntityToken entity) {
        String specId = entity.getCustomEntityType();
        if (specId == null || entity.getMemoryWithoutUpdate().contains(MemFlags.SALVAGE_SPEC_ID_OVERRIDE)) {
            specId = entity.getMemoryWithoutUpdate().getString(MemFlags.SALVAGE_SPEC_ID_OVERRIDE);
        }
        if (specId == null) return null;
        return SalvageEntityGeneratorOld.getSalvageSpec(specId);
    }

    /**
     * The loot of scavenging, once, the field that SalvageEntity.convertToDebrisField(FIELD_CONTENT_MULTIPLIER_AFTER_SALVAGE)
     * would spawn, as SalvageEntity.performSalvage computes it for a debris field reached by ScavengeAbility.
     */
    private static CargoAPI scavengeOfLeftoverField(SectorEntityToken entity, SalvageEntityGenDataSpec spec) {
        float valueMult = SalvageEntity.FIELD_CONTENT_MULTIPLIER_AFTER_SALVAGE;
        float rating = spec.getSalvageRating();

        // convertToDebrisField: radius and density of the new field.
        float radius = 200f + rating * 400f;
        float density = 0.5f + rating * 0.5f;

        // convertToDebrisField: the field's drops. Vanilla adds value drops without the content multiplier
        // (it adds data.clone(), not the scaled copy) and random drops with it. Kept exactly so.
        List<DropData> dropValue = new ArrayList<>();
        List<DropData> dropRandom = new ArrayList<>();
        for (DropData data : spec.getDropValue()) dropValue.add(data.clone());
        for (DropData data : spec.getDropRandom()) {
            DropData copy = data.clone();
            copy.valueMult = valueMult;
            dropRandom.add(copy);
        }
        for (DropData data : entity.getDropValue()) dropValue.add(data.clone());
        for (DropData data : entity.getDropRandom()) {
            DropData copy = data.clone();
            copy.valueMult = valueMult;
            dropRandom.add(copy);
        }

        // convertToDebrisField: the field's salvage seed, then performSalvage's Random.
        long seed = entity.getMemoryWithoutUpdate().getLong(MemFlags.SALVAGE_SEED);
        Random random;
        if (seed != 0) {
            long fieldSeed = Misc.getRandom(seed, 150).nextLong();
            random = Misc.getRandom(fieldSeed, 100);
        } else {
            random = new Random();
        }

        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        float valueMultFleet = valueRecovery(fleet, debrisReqMult(radius)).getModifiedValue();
        float rareItemSkillMult = fleet.getStats().getDynamic().getValue(Stats.SALVAGE_VALUE_MULT_FLEET_INCLUDES_RARE);
        float fuelMult = fleet.getStats().getDynamic().getValue(Stats.FUEL_SALVAGE_VALUE_MULT_FLEET);

        // computeOverallMultForDebrisField on a fresh field: density / baseDensity (1) * fraction per attempt.
        float overallMult = density * SalvageEntity.FIELD_SALVAGE_FRACTION_PER_ATTEMPT;
        if (overallMult < SalvageEntity.FIELD_MIN_SALVAGE_MULT) overallMult = SalvageEntity.FIELD_MIN_SALVAGE_MULT;

        return SalvageEntity.generateSalvage(random, valueMultFleet, rareItemSkillMult, overallMult, fuelMult, dropValue, dropRandom);
    }

    /** SalvageEntity.getDebrisReqMult for a field of this radius. */
    private static float debrisReqMult(float radius) {
        float f = (radius - SalvageEntity.FIELD_RADIUS_FOR_BASE_REQ)
                / (float) (SalvageEntity.FIELD_RADIUS_FOR_MAX_REQ - SalvageEntity.FIELD_RADIUS_FOR_BASE_REQ);
        if (f < 0) f = 0;
        if (f > 1) f = 1;
        return 1f + (SalvageEntity.FIELD_RADIUS_MAX_REQ_MULT - 1f) * f;
    }

    /** SalvageEntity.getValueRecoveryStat(true) with the field's crew and machinery needs (computeRequiredToSalvage). */
    private static MutableStat valueRecovery(CampaignFleetAPI fleet, float reqMult) {
        CargoAPI cargo = fleet.getCargo();
        int reqCrew = Math.round((int) (SalvageEntity.BASE_CREW * reqMult) / 10f) * 10;
        int reqMachinery = Math.round((int) (SalvageEntity.BASE_MACHINERY * reqMult) / 10f) * 10;

        MutableStat stat = new MutableStat(1f);
        int i = 0;
        float machineryContrib = 0.75f;
        stat.modifyPercent("base", -100f);
        stat.modifyPercent("base_positive", (int) Math.round(100f - 100f * machineryContrib), "Base effectiveness");

        if (reqCrew > 0) {
            float available = (int) cargo.getCommodityQuantity(Commodities.CREW);
            stat.modifyMultAlways("" + i++, Math.min(available / reqCrew, 1f), "Crew available");
        }
        if (reqMachinery > 0) {
            float available = (int) cargo.getCommodityQuantity(Commodities.HEAVY_MACHINERY);
            float val = Math.min(available / reqMachinery, machineryContrib);
            stat.modifyPercentAlways("" + i++, (int) Math.round(val * 100f), "Heavy machinery available");
        }

        for (StatMod mod : fleet.getStats().getDynamic().getStat(Stats.SALVAGE_VALUE_MULT_FLEET_INCLUDES_RARE).getFlatMods().values()) {
            stat.modifyPercentAlways("" + i++, (int) Math.round(mod.value * 100f), mod.desc);
        }
        for (StatMod mod : fleet.getStats().getDynamic().getStat(Stats.SALVAGE_VALUE_MULT_FLEET_NOT_RARE).getFlatMods().values()) {
            stat.modifyPercentAlways("" + i++, (int) Math.round(mod.value * 100f), mod.desc);
        }
        float fleetSalvageShips = SalvageEntity.getPlayerShipsSalvageModUncapped();
        stat.modifyPercentAlways("" + i++, (int) Math.round(fleetSalvageShips * 100f), "Fleetwide salvaging capability");
        return stat;
    }
}
