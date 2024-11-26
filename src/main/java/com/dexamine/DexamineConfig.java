package com.dexamine;

import net.runelite.client.config.*;

@ConfigGroup("dexamine")
public interface DexamineConfig extends Config {
    @ConfigSection(
            name = "Examine Log",
            description = "Custom Examine log things.",
            position = 1
    )
    String logsSection = "logs";

    @ConfigSection(
            name = "Hints",
            description = "Hint settings.",
            position = 2,
            closedByDefault = true
    )
    String hintsSection = "hints";

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
            section = hintsSection
    )
    default boolean enableMenuHints() {
        return true;
    }

    @ConfigItem(
            keyName = "enableExamineHidden",
            name = "Examine Menu Option Hidden",
            description = "Enable hiding examine options once examines are found",
            section = hintsSection
    )
    default boolean enableExamineHidden() {
        return false;
    }

    @ConfigItem(
            keyName = "enableHiddenHints",
            name = "Hide Examine Log Text",
            description = "Enable Showing '...' instead of text",
            section = hintsSection
    )
    default boolean enableHiddenHints() {
        return true;
    }

    @ConfigItem(
            keyName = "hintCount",
            name = "Examine Log Hint Count",
            description = "Shows hints to help find new examine logs",
            section = hintsSection
    )
    default int hintCount() {
        return 3;
    }

    @ConfigItem(
            keyName = "showAllHiddenLogs",
            name = "Show Full List of Examine Logs",
            description = "Shows every examine text to find.",
            warning = "WARNING this may affect performance.",
            section = hintsSection
    )
    default boolean showAllHiddenLogs() {
        return false;
    }
}
