package eu.darkbot.kekawce.modules.tradertmpmodule;

import com.github.manolo8.darkbot.config.Config;
import com.github.manolo8.darkbot.config.types.Num;
import com.github.manolo8.darkbot.config.types.Option;
import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.Dropdown;
import eu.darkbot.api.managers.OreAPI;

import java.util.HashSet;
import java.util.Set;

@Configuration("ore_trader.config")
public class OreTraderConfig {
    @Option(value = "Enabled", description = "Check this to enable this feature/plugin")
    public boolean ENABLE_FEATURE = false;

    @Option(value = "Sell map", description = "Goes to this map to sell resources")
    @Dropdown(options = Maps.class)
    public String SELL_MAP = "Auto";

    @Option(value = "Resources to sell", description = "Will only sell the selected resources")
    @Dropdown(options = Ores.class, multi = true)
    public Set<OreAPI.Ore> ORES_TO_SELL = new HashSet<>(Ores.ORES);

    @Option(value = "Sell Config", description = "Changes to this config when flying/selling")
    public Config.ShipConfig SELL_CONFIG = new Config.ShipConfig(2, '9');

    @Option(value = "Finish current target", description = "Will kill current target before travelling to base to sell")
    public boolean FINISH_TARGET_BEFORE_SELLING = false;

    @Option(value = "Advanced", description = "You can ignore this if you have no issues")
    public Advanced ADVANCED = new Advanced();

    public static class Advanced {
        @Option(
                value = "Wait before starting to sell (ms)",
                description = "This is how long the bot will wait before starting to sell" +
                              "\nIncrease it if you find your ship stuck moving before selling" +
                              "\nOtherwise decrease it if you want to speed up this action"
        )
        @Num(min = 0, max = 5000, step = 100)
        public int SELL_WAIT = 2000;

        @Option(
                value = "Sell delay (ms)",
                description = "This is the delay between selling each resource" +
                              "\nIncrease it if you find that some resources have been skipped during selling." +
                              "\nOtherwise decrease it if you want to speed up selling"
        )
        @Num(min = 0, max = 1000, step = 100)
        public int SELL_DELAY = 300;
    }
}
