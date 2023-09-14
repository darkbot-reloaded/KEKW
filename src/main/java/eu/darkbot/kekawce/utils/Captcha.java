package eu.darkbot.kekawce.utils;

import eu.darkbot.api.game.entities.Box;

import java.util.Collection;

public class Captcha {
    public static boolean exists(Collection<? extends Box> boxes) {
        return boxes.stream()
                .anyMatch(box -> box.getTypeName().equals("POISON_PUSAT_BOX_BLACK") || box.getTypeName().equals("BONUS_BOX_RED"));
    }
}
