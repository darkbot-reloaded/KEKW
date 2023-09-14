package eu.darkbot.kekawce.utils;

import eu.darkbot.api.managers.ExtensionsAPI;

import java.util.Arrays;

public class DefaultInstallable {
    public static String VERSION = null;

    public static <T> boolean cantInstall(ExtensionsAPI api, T feature) {
        if (!Arrays.equals(VerifierChecker.class.getSigners(), feature.getClass().getSigners()))
            return true;
        if (!VerifierChecker.getAuthApi().requireDonor()) return true;

        if (VERSION != null) return false;
        VERSION = api.getFeatureInfo(feature.getClass()).getPluginInfo().getVersion().toString();
        return false;
    }

}
