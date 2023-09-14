package eu.darkbot.kekawce.modules.infectiontmpmodule;

import com.github.manolo8.darkbot.Main;
import com.github.manolo8.darkbot.core.entities.BasePoint;
import com.github.manolo8.darkbot.core.entities.Entity;
import com.github.manolo8.darkbot.core.entities.Portal;
import com.github.manolo8.darkbot.core.entities.bases.BaseRefinery;
import com.github.manolo8.darkbot.core.entities.bases.BaseStation;
import com.github.manolo8.darkbot.core.entities.bases.BaseTurret;
import com.github.manolo8.darkbot.core.entities.bases.QuestGiver;
import com.github.manolo8.darkbot.extensions.features.Feature;
import com.github.manolo8.darkbot.utils.Time;
import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.events.EventHandler;
import eu.darkbot.api.extensions.Behavior;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.managers.BotAPI;
import eu.darkbot.api.managers.ExtensionsAPI;
import eu.darkbot.api.managers.StarSystemAPI;
import eu.darkbot.kekawce.utils.Captcha;
import eu.darkbot.kekawce.utils.DefaultInstallable;
import eu.darkbot.kekawce.utils.StatusUtils;
import eu.darkbot.shared.modules.TemporalModule;

import java.util.Comparator;

@Feature(name = "Auto Infection", description = "drops infection mine when you are not infected")
public class InfectionTmpModule extends TemporalModule implements Behavior, Configurable<InfectionConfig> {

    private static final int INFECT_MINE_ID = 17;
    private static final int INFECT_MINE_EFFECT = 85;

    private Main main;
    private InfectionConfig config;

    private long waitTime;
    private long activeTime;
    private boolean moved;

    public InfectionTmpModule(BotAPI bot, Main main, ExtensionsAPI extensions) {
        super(bot);
        if (DefaultInstallable.cantInstall(extensions, this)) return;
        this.main = main;
    }

    @Override
    public void install(PluginAPI api) {
        super.install(api);
        this.moved = false;
    }

    @EventHandler
    public void onMapChange(StarSystemAPI.MapChangeEvent e) {
        waitTime = 0;
    }

    @Override
    public void setConfig(ConfigSetting<InfectionConfig> config) {
        this.config = config.getValue();
    }

    @Override
    public boolean canRefresh() {
        return false;
    }

    @Override
    public String getStatus() {
        long activeFor = System.currentTimeMillis() - this.activeTime;
        return StatusUtils.status("Auto Infection",
                (isSafe() ? "Infecting..." : "Not safe aborting infection"), activeFor + "ms");
    }

    @Override
    public void onTickBehavior() {
        if (!config.ENABLE_FEATURE) return;
        if (!canInfect() || System.currentTimeMillis() - this.activeTime < 60 * Time.SECOND) return;
        if (Captcha.exists(main.mapManager.entities.boxes)) return;

        if (waitTime == 0) waitTime = System.currentTimeMillis();
        if (!this.equals(this.main.getModule()) && System.currentTimeMillis() - waitTime > 15 * Time.SECOND) {
            this.activeTime = 0;
            main.setModule(this);
        }
    }

    @Override
    public void onTickModule() {
        if (activeTime == 0) activeTime = System.currentTimeMillis();

        infect();

        if (isInfected() || isUnderAttack() || !isSafe()
                || System.currentTimeMillis() - activeTime > 30 * Time.SECOND) {
            this.moved = false;
            goBack();
        }
    }

    private void infect() {
        if (!this.moved) {
            this.moved = true;
            this.main.hero.drive.stop(true);
        }

        this.main.hero.setMode(config.INFECT_CONFIG);

        if (!this.main.hero.drive.isMoving()) {
            Main.API.keyboardClick(this.config.INFECT_KEY);
        }

        this.main.mapManager.entities.mines.stream()
                .filter(m -> m.typeId == INFECT_MINE_ID)
                .findFirst()
                .ifPresent(infectionMine -> this.main.hero.drive.move(infectionMine));
    }

    private boolean canInfect() {
        return canLayMines() && !isInfected() && !isUnderAttack() && onWorkingMap() && isSafe();
    }

    private boolean canLayMines() {
        return !isInDemiZone() && main.facadeManager.slotBars.categoryBar
                .findItemById("ammunition_mine_im-01")
                .filter(item -> item.activatable && item.quantity > 0)
                .isPresent();
    }

    private boolean onWorkingMap() {
        return this.main.config.GENERAL.WORKING_MAP == this.main.hero.map.id;
    }

    private boolean isInfected() {
        return this.main.hero.hasEffect(INFECT_MINE_EFFECT);
    }

    private boolean isUnderAttack() {
        return this.main.mapManager.entities.ships.stream().anyMatch(s -> s.playerInfo.isEnemy() && s.isAttacking(this.main.hero));
    }

    private boolean isInDemiZone() {
        Portal closestPort = this.main.mapManager.entities.portals.stream()
                .min(Comparator.comparingDouble(s -> this.main.hero.locationInfo.now.distance(s)))
                .orElse(null);

        BasePoint closestBase = this.main.mapManager.entities.basePoints.stream()
                .min(Comparator.comparingDouble(s -> this.main.hero.locationInfo.now.distance(s)))
                .orElse(null);

        boolean isOnPort = closestPort != null && this.main.hero.locationInfo.distance(closestPort) < radius(closestPort);
        boolean isOnBase = closestBase != null && this.main.hero.locationInfo.distance(closestBase) < radius(closestBase);

        return isOnBase || isOnPort;
    }

    private double diameter(Entity entity) {
        if (this.main.hero.map.id == 92) { // 5-2 base
            if (entity instanceof BaseStation) return 4000;
            if (entity instanceof BaseRefinery) return 3500;
        }
        if (entity instanceof Portal) return 2700;
        if (entity instanceof QuestGiver) return 2000;
        if (entity instanceof BaseTurret) return 1500; // x-8
        return 2500;
    }

    private double radius(Entity entity) {
        return diameter(entity) / 2;
    }

    private boolean isSafe() {
        return hasEnoughHp() && npcsInRangeLessThanX();
    }

    private boolean hasEnoughHp() {
        return this.main.hero.health.hpPercent() > this.config.MIN_HP;
    }

    private boolean npcsInRangeLessThanX() {
        return this.main.mapManager.entities.npcs.size() < this.config.MAX_NPCS_IN_VISION;
    }

}
