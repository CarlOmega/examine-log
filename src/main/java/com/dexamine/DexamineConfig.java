package com.dexamine;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("dexamine")
public interface DexamineConfig extends Config
{
	@ConfigSection(
			name = "Logs",
			description = "What examine logs are we tracking?",
			position = 1
	)
	String logsSection = "logs";

	@ConfigItem(keyName = "enableCustomCollectionLog", name = "Custom Collection Log Section", description = "Enable Custom Collection Log Section", section = logsSection)
	default boolean enableCustomCollectionLog() {
		return true;
	}
	@ConfigItem(keyName = "trackItem", name = "Track Items", description = "Track Item Logs", section = logsSection)
	default boolean trackItem() {
		return true;
	}

	@ConfigItem(keyName = "trackNPC",name = "Track NPCs", description = "Track NPC Logs", section = logsSection)
	default boolean trackNPC() {
		return true;
	}

	@ConfigItem(keyName = "trackObject", name = "Track Objects", description = "Track Object Logs", section = logsSection)
	default boolean trackObject() {
		return true;
	}
}
