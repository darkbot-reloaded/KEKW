package eu.darkbot.kekawce.modules.chromintmpmodule;

import com.github.manolo8.darkbot.Main;
import com.github.manolo8.darkbot.core.manager.MapManager;
import com.github.manolo8.darkbot.core.utils.Drive;
import com.github.manolo8.darkbot.extensions.features.Feature;
import com.github.manolo8.darkbot.utils.Time;
import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.events.EventHandler;
import eu.darkbot.api.extensions.Behavior;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.extensions.Task;
import eu.darkbot.api.game.entities.Box;
import eu.darkbot.api.game.entities.Entity;
import eu.darkbot.api.game.entities.Npc;
import eu.darkbot.api.game.galaxy.GalaxyGate;
import eu.darkbot.api.game.galaxy.GateInfo;
import eu.darkbot.api.game.other.Locatable;
import eu.darkbot.api.game.other.Location;
import eu.darkbot.api.managers.BotAPI;
import eu.darkbot.api.managers.ChrominAPI;
import eu.darkbot.api.managers.EntitiesAPI;
import eu.darkbot.api.managers.ExtensionsAPI;
import eu.darkbot.api.managers.GalaxySpinnerAPI;
import eu.darkbot.api.managers.GameLogAPI;
import eu.darkbot.api.managers.HeroAPI;
import eu.darkbot.api.managers.MovementAPI;
import eu.darkbot.api.managers.StatsAPI;
import eu.darkbot.kekawce.utils.Captcha;
import eu.darkbot.kekawce.utils.DefaultInstallable;
import eu.darkbot.kekawce.utils.StatusUtils;
import eu.darkbot.shared.modules.TemporalModule;

import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Feature(name = "Zeta Chromin Farmer", description = "suicides on last wave in zeta for more chromin")
public class ChrominFarmerTmpModule extends TemporalModule implements Behavior, Task, Configurable<ChrominFarmerConfig> {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("HH:mm:ss");

    private enum ChrominFarmerState {
        COLLECTING,
        SUICIDING,
        WAITING;

        @Override
        public String toString() {
            return this.name().charAt(0) + this.name().substring(1).toLowerCase();
        }
    }
    private ChrominFarmerTmpModule.ChrominFarmerState chrominFarmerState = ChrominFarmerState.WAITING;

    private double totalAmt = -1D, earnedAmt;

    private static final int ZETA_LAST_MAP_ID = 73; // name: "GG ζ 3", last map in zeta

    private final BotAPI main;
    private final HeroAPI hero;
    private final MovementAPI movement;
    private final GalaxySpinnerAPI spinner;
    private final StatsAPI stats;
    private final ChrominAPI chromin;
    private final Collection<? extends Npc> npcs;
    private final Collection<? extends Box> boxes;
    private final ChrominCollector collector;

    private ChrominFarmerConfig config;
    private GateInfo gate;



    private int livesBought;

    private long lastStatsCheck;
    private boolean isLastStatsInitialized;

    private boolean canSuicideInRadZone;
    private boolean hasSeenLastSubWave;
    private int currWave = -1;
    private int currLives = -1;
    private int lastMapId = -1;

    public ChrominFarmerTmpModule(BotAPI bot,
                                  ExtensionsAPI extensions,
                                  HeroAPI hero,
                                  MovementAPI movement,
                                  GalaxySpinnerAPI spinner,
                                  StatsAPI stats,
                                  ChrominAPI chromin,
                                  EntitiesAPI entities,
                                  ChrominCollector collector) {
        super(bot);
        if (DefaultInstallable.cantInstall(extensions, this)) throw new SecurityException();

        this.main = bot;
        this.hero = hero;
        this.movement = movement;
        this.spinner = spinner;
        this.stats = stats;
        this.chromin = chromin;
        this.npcs = entities.getNpcs();
        this.boxes = entities.getBoxes();
        this.collector = collector;

        this.gate = spinner.getGalaxyInfo().getGateInfo(GalaxyGate.ZETA);
    }

    @Override
    public void install(PluginAPI api) {
        super.install(api);
        this.chrominFarmerState = ChrominFarmerState.WAITING;
        this.gate = spinner.getGalaxyInfo().getGateInfo(GalaxyGate.ZETA);
    }

    @Override
    public void setConfig(ConfigSetting<ChrominFarmerConfig> config) {
        this.config = config.getValue();
        this.collector.setConfig(this.config);
    }

    @Override
    public String getStatus() {
        return StatusUtils.status("Chromin Farmer", chrominFarmerState.toString(),
                (currWave >= 26 ? "2nd devourer" : "1st devourer"));
    }

    @Override
    public boolean canRefresh() {
        return false;
    }

    @Override
    public void onTickTask() {
        if (!config.ENABLE_FEATURE) return;
        buyLivesForZeta();
        updateStats();
    }

    @Override
    public void onTickBehavior() {
        if (!config.ENABLE_FEATURE) return;
        this.collector.onTickModule();

        if (!canStartChrominFarmingModule()) return;
        if (Captcha.exists(boxes)) return;

        if (!this.equals(main.getModule())) main.setModule(this);
    }

    @Override
    public void onTickModule() {
        chrominFarmerTick();
    }

    private void chrominFarmerTick() {
        this.chrominFarmerState = getChrominFarmerState();

        switch (this.chrominFarmerState) {
            case COLLECTING:
                if (this.hero.getLocalTarget() != null && this.hero.isAttacking(hero.getLocalTarget()))
                    hero.triggerLaserAttack();
                this.hero.setMode(this.config.COLLECTOR.COLLECT_CONFIG);
                this.collector.collectBox();
                break;
            case SUICIDING:
                if (this.hero.getLocalTarget() != null && this.hero.isAttacking(hero.getLocalTarget()))
                    hero.triggerLaserAttack();
                this.hero.setMode(this.config.COLLECTOR.SUICIDE_CONFIG);

                Locatable devourer = npcs.stream()
                        .filter(npc -> npc.getEntityInfo().getUsername().contains("Devourer"))
                        .findFirst()
                        .map(Entity::getLocationInfo)
                        .orElseGet(() -> npcs.stream().findFirst().map(Entity::getLocationInfo).orElseGet(hero::getLocationInfo));
                if (!canSuicideInRadZone) canSuicideInRadZone = config.SUICIDE_IN_RAD_ZONE || devourerIsBugged(devourer);
                if (canSuicideInRadZone) moveUnsafe(getClosestRadZone());
                else movement.moveTo(devourer);
                break;
            case WAITING:
                hasSeenLastSubWave = canSuicideInRadZone = false;
                goBack();
                break;
        }
    }

    @SuppressWarnings("deprecation")
    private void moveUnsafe(Locatable loc) {
        ((Drive) movement)
                .clickCenter(true, new com.github.manolo8.darkbot.core.utils.Location(loc.getX(), loc.getY()));
    }

    private boolean devourerIsBugged(Locatable devourer) {
        return hero.distanceTo(devourer) < 200D && !hero.getHealth().hpDecreasedIn(Time.MINUTE);
    }

    private Locatable getClosestRadZone() {
        double width = MapManager.internalWidth, height = MapManager.internalHeight;
        Location currLoc = hero.getLocationInfo();
        double percentX = currLoc.x() / width, percentY = currLoc.y() / height;

        if (Math.abs(percentX - 0.5) > Math.abs(percentY - 0.5)) {
            int sign = percentX < 0.5 ? -1 : 1;
            return Location.of(currLoc.x() + sign * 100, currLoc.y());
        }
        int sign = percentY < 0.5 ? -1 : 1;
        return Location.of(currLoc.x(), currLoc.y() + sign * 100);
    }

    private ChrominFarmerTmpModule.ChrominFarmerState getChrominFarmerState() {
        if (canStartChrominFarmingModule()) {
            return this.collector.hasBoxes()
                    ? ChrominFarmerState.COLLECTING
                    : ChrominFarmerState.SUICIDING;
        }
        return ChrominFarmerState.WAITING;
    }

    private boolean canStartChrominFarmingModule() {
        if (isNotInitialized()) return false;
        if (currLives == -1 || this.hero.getMap().getId() != lastMapId) {
            currLives = gate.getLivesLeft();
            lastMapId = this.hero.getMap().getId();
            return false;
        }
        if (currLives <= 1 || this.hero.getMap().getId() != ZETA_LAST_MAP_ID) return false;

        currWave = getWave();
        if (currWave == -1) return false;
        if (config.ZETA_WAVE == 0 && !(24 <= currWave)) return false;
        if (config.ZETA_WAVE == 1 && !(26 <= currWave)) return false;

        String subwave = Waves.SUB_WAVES.contains(this.config.ZETA_SUB_WAVE) ? config.ZETA_SUB_WAVE : null;
        boolean failsafe = 26 <= currWave && config.ZETA_WAVE == 0;

        if (subwave == null || subwave.equals("All npcs gone (only devourer left)") || failsafe) {
            this.hasSeenLastSubWave = hasSeenLastSubWave ||
                    (24 <= currWave && currWave < 26
                    ? this.npcs.stream().anyMatch(npc -> npc.getEntityInfo().getUsername().contains("Infernal"))
                    : this.npcs.stream().anyMatch(npc -> npc.getEntityInfo().getUsername().contains("Kristallin")));

            return hasSeenLastSubWave && npcs.size() == 1;
        } else {
            return this.npcs.stream().anyMatch(npc -> npc.getEntityInfo().getUsername().contains(subwave));
        }
    }

    private int getWave() {
        return npcs.stream()
                .filter(npc -> npc.getEntityInfo().getUsername().contains("Devourer"))
                .findFirst()
                .map(npc -> {
                    String name = npc.getEntityInfo().getUsername();
                    return Integer.parseInt(name.substring(name.length() - 2));
                })
                .orElse(-1);
    }

    private boolean isNotInitialized() {
        if (gate != null) return false;
        gate = spinner.getGalaxyInfo().getGateInfo(GalaxyGate.ZETA);
        return true;
    }

    private void buyLivesForZeta() {
        if (isNotInitialized()) return;
        if (gate.getLivesLeft() == -1) return;

        this.livesBought = (int) (Math.log((float) gate.getLifePrice() / config.FIRST_LIFE_COST) / Math.log(2));
        if (livesBought < 0) livesBought = 0;

        if (this.livesBought >= this.config.BUY_LIVES) return;

        setStatsStatus("Buying Li" + (this.config.BUY_LIVES == 1 ? "fe" : "ves"));
        int numLivesToBuy = this.config.BUY_LIVES - livesBought;
        if (numLivesToBuy > 0) spinner.buyLife(GalaxyGate.ZETA, 0);
    }

    private void updateStats() {
        if (!isLastStatsInitialized) setStatsStatus("Initializing...");
        if (isNotInitialized()) return;

        if (lastStatsCheck == 0) lastStatsCheck = System.currentTimeMillis();

        if ((System.currentTimeMillis() - lastStatsCheck) > Time.SECOND) {
            this.isLastStatsInitialized = true;
            this.lastStatsCheck = 0;

            setStatsStatus(this.chrominFarmerState.toString());
            updateStats("Lives Left", currLives);
            updateStats("Life Price", gate.getLifePrice());
            updateStats("Lives Bought", this.livesBought);

            if (this.chromin.getMaxAmount() == 0) return;

            updateChromin(this.chromin.getCurrentAmount());

            updateStats("Total Chromin", (int)(this.totalAmt));
            updateStats("Chromin Gained", (int)(this.earnedAmt));
            updateStats("Chromin Per Hr", (int)(this.earnedAmt / (stats.getRunningTime().toMillis() / (double)Time.HOUR)));
        }
    }

    public void updateChromin(double currAmt) {
        if (this.totalAmt == -1D) {
            this.totalAmt = currAmt;
            return;
        }
        if (currAmt <= 0) return;

        double diff = currAmt - this.totalAmt;
        if (diff > 0) earnedAmt += diff;
        this.totalAmt = currAmt;
    }

    private synchronized void setStatsStatus(String status) {
        this.config.STATUS_UPDATE.send("[" + this.DATE_FORMAT.format(new Date()) + "] " + status);
    }

    private void updateStats(String key, Integer value) {
        synchronized (config.lock) {
            this.config.STATS_INFO.put(key, value);
            this.config.STATS_INFO_UPDATE.send(key);
        }
    }

}
