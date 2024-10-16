package com.dexamine;

import net.runelite.client.config.*;

@ConfigGroup("dexamine")
public interface DexamineConfig extends Config {
    @ConfigSection(
            name = "Collection Log",
            description = "Custom Collection log things.",
            position = 1
    )
    String logsSection = "logs";

    @ConfigItem(
            keyName = "enableCollectionLogPopup",
            name = "Custom Collection Log Popup",
            description = "Enable Collection Log Popup",
            section = logsSection
    )
    default boolean enableCollectionLogPopup() {
        return true;
    }

    @ConfigItem(
            keyName = "enableCustomCollectionLog",
            name = "Custom Examine Log Interface",
            description = "Enable Custom Examine Log Interface, right click collection log in character summary.",
            section = logsSection
    )
    default boolean enableCustomCollectionLog() {
        return true;
    }

    @ConfigItem(
            keyName = "enableMenuHints",
            name = "Examine Menu Option Hints",
            description = "Enable Showing '*' for unseen examines",
            section = logsSection
    )
    default boolean enableMenuHints() {
        return true;
    }

    @Range(
            max = 20
    )
    @ConfigItem(
            keyName = "hintCount",
            name = "Examine Log Hint Count",
            description = "Shows ... to help finding new examine texts",
            section = logsSection
    )
    default int hintCount() {
        return 0;
    }
}
