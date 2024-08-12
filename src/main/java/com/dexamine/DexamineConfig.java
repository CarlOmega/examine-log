package com.dexamine;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("dexamine")
public interface DexamineConfig extends Config
{
	@ConfigSection(
			name = "Collection Log",
			description = "Custom Collection log things.",
			position = 1,
			closedByDefault = true
	)
	String logsSection = "logs";

	@ConfigSection(
			name = "Examine Types",
			description = "What examine logs are we tracking?",
			position = 2
	)
	String examineTypes = "types";

	@ConfigItem(keyName = "enableCollectionLogPopup", name = "Custom Collection Log Popup", description = "Enable Collection Log Popup", section = logsSection)
	default boolean enableCollectionLogPopup() {
		return true;
	}
	@ConfigItem(keyName = "enableCustomCollectionLog", name = "Custom Collection Log Entries", description = "Enable Custom Collection Log Entries", section = logsSection)
	default boolean enableCustomCollectionLog() {
		return true;
	}
	@ConfigItem(keyName = "trackItem", name = "Track Items", description = "Track Item Logs", section = examineTypes)
	default boolean trackItem() {
		return true;
	}

	@ConfigItem(keyName = "trackNPC",name = "Track NPCs", description = "Track NPC Logs", section = examineTypes)
	default boolean trackNPC() {
		return true;
	}

	@ConfigItem(keyName = "trackObject", name = "Track Objects", description = "Track Object Logs", section = examineTypes)
	default boolean trackObject() {
		return true;
	}
}
