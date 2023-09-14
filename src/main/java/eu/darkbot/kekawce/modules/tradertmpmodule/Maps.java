package eu.darkbot.kekawce.modules.tradertmpmodule;

import eu.darkbot.api.config.annotations.Dropdown;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class Maps implements Dropdown.Options<String> {
    private static final List<String> OPTIONS = Arrays.asList("X-1", "X-8", "5-2", "LoW");

    @Override
    public String getText(String option) {
        return option;
    }

    @Override
    public Collection<String> options() {
        return OPTIONS;
    }

}
