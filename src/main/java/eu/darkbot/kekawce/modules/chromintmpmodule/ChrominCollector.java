package eu.darkbot.kekawce.modules.chromintmpmodule;

import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.events.EventHandler;
import eu.darkbot.api.events.Listener;
import eu.darkbot.api.game.entities.Box;
import eu.darkbot.api.game.other.Locatable;
import eu.darkbot.api.game.other.Location;
import eu.darkbot.api.managers.EntitiesAPI;
import eu.darkbot.api.managers.StarSystemAPI;
import eu.darkbot.shared.modules.CollectorModule;
import eu.darkbot.util.TimeUtils;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

public class ChrominCollector extends CollectorModule implements Listener {

    private static final String CHROMIN_BOX = "CHROMIN_BOX";

    private ChrominFarmerConfig config;
    public final Map<String, Box> chrominBoxes = new HashMap<>();

    private long waitingForBoxUntil;

    public ChrominCollector(PluginAPI api) {
        super(api);
        this.waitingForBoxUntil = -1;
    }

    public void setConfig(ChrominFarmerConfig config) {
        this.config = config;
    }

    @EventHandler
    public void onMapChange(StarSystemAPI.MapChangeEvent mapChange) {
        this.chrominBoxes.clear();
    }

    @EventHandler
    private void onEntityReceived(EntitiesAPI.EntityCreateEvent entity) {
        if (entity instanceof Box) {
            Box box = (Box) entity;
            if (box.getTypeName().contains(CHROMIN_BOX)) {
                chrominBoxes.put(box.getHash(), box);
            }
        }
    }

    @Override
    public void onTickModule() {
        this.chrominBoxes.values().removeIf(box -> this.hero.distanceTo(box) < 700D && !box.isValid());
    }

    public boolean hasBoxes() {
        Box oldCurr = currentBox;
        if (isNotWaiting()) findBox();
        if (currentBox == null || oldCurr == null || currentBox != oldCurr) waitingForBoxUntil = -1;

        Box curr = currentBox != null ? currentBox : this.chrominBoxes.values().stream()
                .min(Comparator.comparingDouble(this.hero::distanceTo))
                .orElse(null);
        return curr != null;
    }

    public void collectBox() {
        if (currentBox == null) {
            this.chrominBoxes.values().stream()
                    .min(Comparator.comparingDouble(hero::distanceTo))
                    .ifPresent(box -> moveTowardsBox(box.getLocationInfo().getCurrent()));
        } else if (config.COLLECTOR.PET_BOX_COLLECTING_ONLY && currentBox.getTypeName().contains(CHROMIN_BOX)) {
            moveTowardsBox(currentBox);

            if (this.hero.distanceTo(currentBox) < 450D) {
                boolean petStuckOnCargo = stats.getCargo() >= stats.getMaxCargo() &&
                        this.boxes.stream().anyMatch(box -> isResource(box.getTypeName()));
                if (waitingForBoxUntil == -1 || petStuckOnCargo) waitingForBoxUntil = System.currentTimeMillis() + 10 * TimeUtils.SECOND;
                else if (System.currentTimeMillis() > waitingForBoxUntil) super.collectBox();
            } else waitingForBoxUntil = -1;
        } else super.collectBox();
    }

    private void moveTowardsBox(Locatable box) {
        box = Location.of(box, box.angleTo(movement.getCurrentLocation()) - 0.3, 200);
        if (movement.getDestination().distanceTo(box) > 300) movement.moveTo(box);
    }

    @Override
    public String toString() {
        StringBuilder locInfo = new StringBuilder();

        chrominBoxes.values().stream().sorted(Comparator.comparingDouble(hero::distanceTo))
                .forEach(box -> locInfo.append("(").append(box.getLocationInfo().getCurrent().toString()).append(") | "));

        return locInfo.toString();
    }

}
