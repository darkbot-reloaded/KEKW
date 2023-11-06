package eu.darkbot.kekawce.modules.tradertmpmodule;

import com.github.manolo8.darkbot.utils.I18n;
import com.github.manolo8.darkbot.utils.Time;
import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.extensions.Behavior;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.extensions.Feature;
import eu.darkbot.api.game.entities.Portal;
import eu.darkbot.api.game.entities.Station;
import eu.darkbot.api.game.other.GameMap;
import eu.darkbot.api.game.other.Location;
import eu.darkbot.api.managers.*;
import eu.darkbot.kekawce.utils.Captcha;
import eu.darkbot.kekawce.utils.DefaultInstallable;
import eu.darkbot.kekawce.utils.StatusUtils;
import eu.darkbot.shared.modules.TemporalModule;
import eu.darkbot.shared.utils.MapTraveler;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Feature(name = "Ore Trader", description = "When cargo is full travels to base to sell")
public class OreTraderTmpModule extends TemporalModule implements Behavior, Configurable<OreTraderConfig> {

    private final HeroAPI hero;
    private final StatsAPI stats;
    private final OreAPI oreTrade;
    private final EntitiesAPI entities;
    private final MovementAPI movement;
    private final MapTraveler traveler;
    private final StarSystemHelper starSystemHelper;

    private OreTraderConfig config;
    private Portal ggExitPortal;
    private GameMap targetMap;

    private Iterator<OreAPI.Ore> ores;
    private long sellTime, sellTimeout = Long.MAX_VALUE, waitUntil;
    private boolean hasAttemptedToSell, hasClickedTradeBtn;
    private Status status = Status.IDLE;

    private enum Status {
        IDLE("Module not enabled"),
        SELLING("Selling ore"),
        NAVIGATING_BASE("Navigating to Base Map"),
        NAVIGATING_REFINERY("Navigating to Refinery");

        private final String message;

        Status(String message) {
            this.message = message;
        }
    }

    public OreTraderTmpModule(BotAPI bot,
                              ExtensionsAPI extensions,
                              HeroAPI hero,
                              StatsAPI stats,
                              EntitiesAPI entities,
                              OreAPI oreTrade,
                              StarSystemAPI star,
                              MovementAPI movement,
                              MapTraveler traveler) {
        super(bot);
        if (!Arrays.equals(DefaultInstallable.class.getSigners(), getClass().getSigners())) throw new SecurityException();
        if (DefaultInstallable.cantInstall(extensions, this)) throw new SecurityException();
        
        this.hero = hero;
        this.stats = stats;
        this.oreTrade = oreTrade;
        this.entities = entities;
        this.movement = movement;
        this.traveler = traveler;
        this.starSystemHelper = new StarSystemHelper(hero, star, entities);
    }

    @Override
    public String getStatus() {
        String state = status.message;
        if (status == Status.NAVIGATING_BASE) {
            state += " | " + I18n.get("module.map_travel.status.no_next", targetMap.getName());
        }
        return StatusUtils.status("Ore Trader", state);
    }

    @Override
    public void setConfig(ConfigSetting<OreTraderConfig> config) {
        this.config = config.getValue();
    }

    @Override
    public boolean canRefresh() {
        return !hero.isMoving() && !hasAttemptedToSell;
    }

    @Override
    public void onTickBehavior() {
        if (shouldEnableModule()) enableModule();
    }

    @Override
    public void onTickModule() {
        if (shouldGoBackEarly()) goBack();

        sellTick();

        if (!finishedSellingOres() && !oreSellBtnsAreBugged()) return;
        if (oreTrade.canSellOres()) {
            System.out.println("closing trade");
            oreTrade.showTrade(false, null);
        } else goBack();
    }

    @Override
    public void goBack() {
        sellTime = 0;
        sellTimeout = Long.MAX_VALUE;
        ggExitPortal = null;
        targetMap = null;
        hasClickedTradeBtn = false;
        hasAttemptedToSell = false;
        status = Status.IDLE;

        super.goBack();
    }

    private boolean shouldEnableModule() {
        if (!config.ENABLE_FEATURE) return false;

        boolean hasTarget = hero.getTarget() != null && hero.getTarget().isValid();
        return !Captcha.exists(entities.getBoxes()) // no captcha is active
                && !config.ORES_TO_SELL.isEmpty() // we have configured ores
                && (!config.FINISH_TARGET_BEFORE_SELLING || !hasTarget) // not waiting for target to finish
                && (stats.getCargo() >= stats.getMaxCargo() && stats.getMaxCargo() != 0) // cargo is full
                && checkGG(); // we can safely exit a GG
    }

    private void enableModule() {
        if (bot.getModule() != this) bot.setModule(this);
    }

    // bug that causes you to be unable to sell ores
    private boolean oreSellBtnsAreBugged() {
        return stats.getCargo() >= stats.getMaxCargo() && oreTrade.canSellOres() &&
                sellTimeout <= System.currentTimeMillis();
    }

    private boolean shouldGoBackEarly() {
        return !hasAttemptedToSell && (cargoHasDecreased() || isStuckInGG());
    }

    // to prevent special cases such as auto-refining/upgrading where resources will be used up
    private boolean cargoHasDecreased() {
        return stats.getCargo() < stats.getMaxCargo() - 100;
    }

    /**
     * Indicates that the {@link HeroAPI Hero} is not in the LoW gate and an exit {@link Portal}
     * cannot be found.
     */
    private boolean isStuckInGG() {
        return hero.getMap().isGG() && !hero.getMap().getName().equals("LoW") && ggExitPortal == null;
    }

    private void sellTick() {
        this.hero.setMode(this.config.SELL_CONFIG);


        if (targetMap == null) targetMap = starSystemHelper.getRefineryMap(config);
        if (!navigateToTargetMap()) return;

        Optional<Station.Refinery> refinery = starSystemHelper.findRefinery();
        refinery.ifPresent(this::travelToBaseAndSell);
    }

    /**
     * Navigates to the target map. If already there, returns true. Otherwise, false.
     */
    private boolean navigateToTargetMap() {
        if (targetMap.getId() == hero.getMap().getId()) return true;

        if (traveler.target.getId() != targetMap.getId()) traveler.setTarget(targetMap);
        status = Status.NAVIGATING_BASE;
        traveler.tick();

        return false;
    }

    private void travelToBaseAndSell(Station.Refinery refinery) {
        if (movement.getDestination().distanceTo(refinery) > 200D
                || (System.currentTimeMillis() - sellTime > 5 * Time.SECOND && sellTime != 0)) { // trade btn not appearing
            status = Status.NAVIGATING_REFINERY;
            double angle = ThreadLocalRandom.current().nextDouble(2 * Math.PI);
            double distance = 100 + ThreadLocalRandom.current().nextDouble(100);
            movement.moveTo(Location.of(refinery.getLocationInfo(), angle, distance));
            this.sellTime = 0;
        } else {
            if (this.sellTime == 0) this.sellTime = System.currentTimeMillis();
            if (!hasClickedTradeBtn && !hero.isMoving() && oreTrade.showTrade(true, refinery)) {
                status = Status.SELLING;
                sellTime = Long.MAX_VALUE;
                sellTimeout = System.currentTimeMillis() + config.ADVANCED.SELL_INTERVAL * config.ORES_TO_SELL.size() + 2L * config.ADVANCED.SELL_WAIT;
                waitUntil = System.currentTimeMillis() + config.ADVANCED.SELL_WAIT;
                hasClickedTradeBtn = true;
            }

            sellOres();
        }
    }

    private void sellOres() {
        if (!oreTrade.canSellOres()) return;

        if (waitUntil > System.currentTimeMillis()) return;
        waitUntil = System.currentTimeMillis() + config.ADVANCED.SELL_INTERVAL;

        if (ores == null || !ores.hasNext()) ores = config.ORES_TO_SELL.iterator();
        if (!ores.hasNext()) return;

        OreAPI.Ore ore = ores.next();
        if (ore == null) return;

        if (ore.isSellable() && !isOreSold(ore)) {
            oreTrade.sellOre(ore);
            hasAttemptedToSell = true;
        }
    }

    private boolean finishedSellingOres() {
        return config.ORES_TO_SELL.stream()
                .filter(Objects::nonNull)
                .allMatch(this::isOreSold);
    }

    private boolean isOreSold(OreAPI.Ore ore) {
        int amount = oreTrade.getAmount(ore);
        return ore == OreAPI.Ore.PALLADIUM
                ? !hero.getMap().getName().equals("5-2") || amount < 15
                : amount <= 0;
    }

    private boolean checkGG() {
        return !hero.getMap().isGG() || hasGateExit() || (targetMap != null && targetMap.getName().equals("LoW") && hero.getMap().getName().equals("LoW"));
    }

    private boolean hasGateExit() {
        ggExitPortal =
                entities.getPortals().stream()
                        .filter(Objects::nonNull)
                        .filter(p -> p.getTargetMap().map(m -> !m.isGG()).orElse(p.getPortalType().getId() == 1))
                        .min(Comparator.comparingDouble(p -> p.getLocationInfo().distanceTo(hero)))
                        .orElse(null);
        return ggExitPortal != null;
    }

}
