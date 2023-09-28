package eu.darkbot.kekawce.modules.tradertmpmodule;

import com.github.manolo8.darkbot.Main;
import com.github.manolo8.darkbot.core.entities.BasePoint;
import com.github.manolo8.darkbot.core.entities.bases.BaseRefinery;
import com.github.manolo8.darkbot.core.manager.HeroManager;
import com.github.manolo8.darkbot.core.manager.StarManager;
import com.github.manolo8.darkbot.core.manager.StatsManager;
import com.github.manolo8.darkbot.core.objects.Map;
import com.github.manolo8.darkbot.core.objects.gui.OreTradeGui;
import com.github.manolo8.darkbot.core.objects.gui.RefinementGui;
import com.github.manolo8.darkbot.core.utils.Drive;
import com.github.manolo8.darkbot.extensions.features.Feature;
import com.github.manolo8.darkbot.utils.I18n;
import com.github.manolo8.darkbot.utils.Time;
import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.extensions.Behavior;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.game.entities.Portal;
import eu.darkbot.api.game.entities.Station;
import eu.darkbot.api.game.other.GameMap;
import eu.darkbot.api.game.other.Location;
import eu.darkbot.api.managers.BotAPI;
import eu.darkbot.api.managers.EntitiesAPI;
import eu.darkbot.api.managers.ExtensionsAPI;
import eu.darkbot.api.managers.OreAPI;
import eu.darkbot.kekawce.utils.Captcha;
import eu.darkbot.kekawce.utils.DefaultInstallable;
import eu.darkbot.kekawce.utils.StatusUtils;
import eu.darkbot.kekawce.utils.VerifierChecker;
import eu.darkbot.shared.modules.TemporalModule;
import eu.darkbot.shared.utils.MapTraveler;
import eu.darkbot.shared.utils.PortalJumper;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

@Feature(name = "Ore Trader", description = "When cargo is full travels to base to sell")
public class OreTraderTmpModule extends TemporalModule implements Behavior, Configurable<OreTraderConfig> {

    private final Main main;
    private final Drive drive;
    private final HeroManager hero;
    private final StatsManager stats;
    private final PortalJumper jumper;
    private final OreAPI oreTrade;
    private final RefinementGui refinement;
    private final MapTraveler traveler;
    private final Collection<? extends Portal> portals;
    private final Collection<? extends Station> bases;

    private OreTraderConfig config;
    private Portal ggExitPortal;

    private Iterator<OreAPI.Ore> ores;
    private long sellTime, sellBtnTime = Long.MAX_VALUE, sellUntil;
    private boolean hasAttemptedToSell, hasClickedTradeBtn;

    public OreTraderTmpModule(BotAPI bot,
                              ExtensionsAPI extensions,
                              Main main,
                              Drive drive,
                              HeroManager hero,
                              StatsManager stats,
                              PortalJumper jumper,
                              OreAPI oreTrade,
                              RefinementGui refinement,
                              MapTraveler traveler,
                              EntitiesAPI entities) {
        super(bot);
        if (!Arrays.equals(DefaultInstallable.class.getSigners(), getClass().getSigners())) throw new SecurityException();
        if (DefaultInstallable.cantInstall(extensions, this)) throw new SecurityException();
        this.main = main;
        this.drive = drive;
        this.hero = hero;
        this.stats = stats;
        this.jumper = jumper;
        this.oreTrade = oreTrade;
        this.refinement = refinement;
        this.traveler = traveler;
        this.portals = entities.getPortals();
        this.bases = entities.getStations();
    }

    @Override
    public String getStatus() {
        Map map = getTargetMap();
        String state, name = map.name;
        if (hero.map.id != map.id)
            state = I18n.get("module.map_travel.status.no_next", name);
        else if (bases.stream().filter(b -> b instanceof BaseRefinery).anyMatch(b -> hero.distanceTo(b) > 300D))
            state = "Travelling to station";
        else state = "Selling";
        return StatusUtils.status("Ore Trader", state, name + " Station");
    }

    @Override
    public void setConfig(ConfigSetting<OreTraderConfig> config) {
        this.config = config.getValue();
    }

    @Override
    public boolean canRefresh() {
        return false;
    }

    @Override
    public void onTickBehavior() {
        if (!config.ENABLE_FEATURE || config.ORES_TO_SELL.isEmpty()) return;
        if (Captcha.exists(main.mapManager.entities.boxes)) return;

        boolean hasTarget = !(this.hero.target == null || this.hero.target.removed);
        if (hasTarget && this.config.FINISH_TARGET_BEFORE_SELLING) return;

        if (stats.getCargo() >= stats.getMaxCargo() && checkGG() && this.main.module != this)
            main.setModule(this);
    }

    @Override
    public void onTickModule() {
        if (shouldGoBackEarly()) goBack();

        sellTick();

        if (!areSelectedResourcesSold() && !oreSellBtnsAreBugged()) return;
        if (oreTrade.canSellOres()) {
            sellTime = 0;
            sellBtnTime = Long.MAX_VALUE;
            hasClickedTradeBtn = false;
            System.out.println("closing trade");
            oreTrade.showTrade(false, null);
        }
        else goBack();
    }

    @Override
    public void goBack() {
        ggExitPortal = null;
        hasAttemptedToSell = false;

        super.goBack();
    }

    // bug that causes you to be unable to sell ores
    private boolean oreSellBtnsAreBugged() {
        return stats.getCargo() >= stats.getMaxCargo() && oreTrade.canSellOres() &&
                sellBtnTime <= System.currentTimeMillis();
    }

    private boolean shouldGoBackEarly() {
        return !hasAttemptedToSell && (cargoHasDecreased() || isStuckInGG());
    }

    // to prevent special cases such as auto-refining/upgrading where resources will be used up
    private boolean cargoHasDecreased() {
        return stats.getCargo() < stats.getMaxCargo() - 100;
    }

    // to prevent bug where bot will get stuck in GG due to jumping into wrong portal (most likely due to some client/server de-sync)
    private boolean isStuckInGG() {
        return hero.map.gg && !hero.map.name.equals("LoW") && ggExitPortal == null;
    }

    private void sellTick() {
        this.hero.setMode(this.config.SELL_CONFIG);

        if (this.hero.map.gg && this.ggExitPortal != null) {
            exitGG();
            return;
        }

        Map targetMap = getTargetMap();
        if (this.hero.map.id != targetMap.id) {
            this.traveler.setTarget(targetMap);
            this.traveler.tick();
        }
        else {
            this.bases.stream()
                    .filter(b -> b instanceof Station.Refinery)
                    .map(b -> (Station.Refinery) b)
                    .findFirst()
                    .ifPresent(this::travelToBaseAndSell);
        }
    }

    private void travelToBaseAndSell(Station.Refinery b) {
        if (!oreTrade.canSellOres() && // can't move while trade window is open or ores won't be sold (some weird DO bug)
                ((drive.movingTo().distanceTo(b) > 200D) ||
                (System.currentTimeMillis() - sellTime > 5 * Time.SECOND && sellTime != 0))) { // trade btn not appearing
            double angle = ThreadLocalRandom.current().nextDouble(2 * Math.PI);
            double distance = 100 + ThreadLocalRandom.current().nextDouble(100);
            drive.move(Location.of(b.getLocationInfo(), angle, distance));
            System.out.println("moving");
            this.sellTime = 0;
        } else {
            if (this.sellTime == 0) this.sellTime = System.currentTimeMillis();
            if (!hasClickedTradeBtn && !hero.locationInfo.isMoving() && oreTrade.showTrade(true, b)) {
                System.out.println("opening trade");
                hasClickedTradeBtn = true;
                sellTime = Long.MAX_VALUE;
                sellBtnTime = System.currentTimeMillis() + config.ADVANCED.SELL_DELAY * config.ORES_TO_SELL.size() + config.ADVANCED.SELL_WAIT;
                sellUntil = System.currentTimeMillis() + config.ADVANCED.SELL_WAIT;
            }

            sellOres();
        }
    }

    private void sellOres() {
        if (!oreTrade.canSellOres()) return;
        if (sellUntil > System.currentTimeMillis()) return;
        sellUntil = System.currentTimeMillis() + config.ADVANCED.SELL_DELAY;

        if (ores == null || !ores.hasNext()) ores = config.ORES_TO_SELL.iterator();
        if (!ores.hasNext()) return;

        OreAPI.Ore ore = ores.next();
        if (ore == null) return;
        oreTrade.sellOre(ore);
        System.out.println("selling: " + ore);

        hasAttemptedToSell = true;
    }

    private boolean areSelectedResourcesSold() {
        return config.ORES_TO_SELL.stream()
                .filter(Objects::nonNull)
                .allMatch(this::isOreSold);
    }

    private boolean isOreSold(OreAPI.Ore ore) {
        RefinementGui.Ore o = refinement.get(ore);
        System.out.printf("checking if %s(type), %s(ore), is sold", ore, o);

        return o != null &&
                (ore == OreAPI.Ore.PALLADIUM ? !hero.map.name.equals("5-2") || o.getAmount() < 15 : o.getAmount() <= 0);
    }

    private void exitGG() {
        if (this.ggExitPortal.distanceTo(main.hero) > 150D) {
            this.hero.drive.move(ggExitPortal);
            return;
        }

        jumper.jump(ggExitPortal);
    }

    private boolean checkGG() {
        return !hero.map.gg || (getTargetMap().name.equals("LoW") && hero.map.name.equals("LoW")) || existsValidPortal();
    }

    private boolean existsValidPortal() {
        ggExitPortal = portals.stream()
                .filter(Objects::nonNull)
                .filter(p -> p.getTargetMap().map(gm -> !gm.isGG()).orElse(p.getPortalType().getId() == 1))
                .min(Comparator.comparingDouble(p -> p.distanceTo(main.hero)))
                .orElse(null);
        return ggExitPortal != null;
    }

    private Map getTargetMap() {
        String name = config.SELL_MAP.replace("X", String.valueOf(hero.playerInfo.factionId));
        return StarManager.getInstance().byName(name);
    }

}
