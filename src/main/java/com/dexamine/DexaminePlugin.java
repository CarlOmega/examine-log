package com.dexamine;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;

import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetModalMode;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;

import java.util.*;
import java.util.Deque;


@Slf4j
@PluginDescriptor(
	name = "Dexamine"
)
public class DexaminePlugin extends Plugin
{
	private static final String CONFIG_GROUP = "dexamine";
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DexamineConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Gson gson;

	private final Deque<PendingExamine> pending = new ArrayDeque<>();

	private Map<String, ItemExamineLog> itemExamineLogs = new HashMap<>();
	private Map<String, NPCExamineLog> npcExamineLogs = new HashMap<>();
	private Map<String, ObjectExamineLog> objectExamineLogs = new HashMap<>();

	@Override
	protected void startUp() throws Exception
	{
		log.info("Dexamine started!");
		if (config.trackItem()) {
			String json = configManager.getConfiguration(CONFIG_GROUP, "item_logs");
			if (!Strings.isNullOrEmpty(json)) {
				itemExamineLogs = gson.fromJson(json, new TypeToken<Map<String, ItemExamineLog>>() {}.getType());
			}
		}
		if (config.trackNPC()) {
			String json = configManager.getConfiguration(CONFIG_GROUP, "npc_logs");
			if (!Strings.isNullOrEmpty(json)) {
				npcExamineLogs = gson.fromJson(json, new TypeToken<Map<String, NPCExamineLog>>() {}.getType());
			}
		}
		if (config.trackNPC()) {
			String json = configManager.getConfiguration(CONFIG_GROUP, "object_logs");
			if (!Strings.isNullOrEmpty(json)) {
				objectExamineLogs = gson.fromJson(json, new TypeToken<Map<String, ObjectExamineLog>>() {}.getType());
			}
		}

	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("Dexamine stopped!");
		saveLogs();
	}

	void saveLogs() {
		if (config.trackItem() && !itemExamineLogs.isEmpty()) {
			String json = gson.toJson(itemExamineLogs);
			configManager.setConfiguration(CONFIG_GROUP, "item_logs", json);
		}
		if (config.trackNPC() && !npcExamineLogs.isEmpty()) {
			String json = gson.toJson(npcExamineLogs);
			configManager.setConfiguration(CONFIG_GROUP, "npc_logs", json);
		}
		if (config.trackObject() && !objectExamineLogs.isEmpty()) {
			String json = gson.toJson(objectExamineLogs);
			configManager.setConfiguration(CONFIG_GROUP, "object_logs", json);
		}
	}

	@Provides
	DexamineConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DexamineConfig.class);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!event.getMenuOption().equals("Examine"))
		{
			return;
		}

		ChatMessageType type;
		MenuEntry entry = event.getMenuEntry();
		int id;
		String name;
		switch (event.getMenuAction())
		{
			case EXAMINE_ITEM_GROUND: {
				type = ChatMessageType.ITEM_EXAMINE;
				final ItemComposition itemComposition = itemManager.getItemComposition(event.getId());
				id = event.getId();
				name = itemComposition.getName();
				break;
			}
			case CC_OP_LOW_PRIORITY: {
				type = ChatMessageType.ITEM_EXAMINE;
				final ItemComposition itemComposition = itemManager.getItemComposition(event.getItemId());
				id = event.getItemId();
				name = itemComposition.getName();
				break;
			}
			case EXAMINE_NPC: {
				type = ChatMessageType.NPC_EXAMINE;
				NPC npc = entry.getNpc();
				if (npc == null) {
					return;
				}
				id = npc.getId();
				name = npc.getName();
				break;
			}
			case EXAMINE_OBJECT: {
				type = ChatMessageType.OBJECT_EXAMINE;
				TileObject object = findTileObject(client.getPlane(), entry.getParam0(), entry.getParam1(), entry.getIdentifier());
				if (object == null) {
					return;
				}
				ObjectComposition objectDefinition = getObjectComposition(object.getId());
				if (objectDefinition == null) {
					return;
				}
				id = object.getId();
				name = objectDefinition.getName();
				break;
			}
			default:
				return;
		}

		PendingExamine pendingExamine = new PendingExamine();
		pendingExamine.setType(type);
		pendingExamine.setId(id);
		pendingExamine.setName(name);
		pending.push(pendingExamine);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		PendingExamine pendingExamine;
		if (!pending.isEmpty()) {
			pendingExamine = pending.poll();
		} else {
			return;
		}

		String text = Text.removeTags(event.getMessage());

		switch (event.getType())
		{
			case ITEM_EXAMINE:
				if (text.startsWith("Price of ")) {
					return;
				}
			case OBJECT_EXAMINE:
			case NPC_EXAMINE:
				if (pendingExamine.getType() != event.getType())
				{
					log.debug("Type mismatch for pending examine: {} != {}", pendingExamine.getType(), event.getType());
					pending.clear();
					return;
				}
				break;
			default:
				return;
		}

		log.debug("Got examine type {} {}: {}", pendingExamine.getType(), pendingExamine.getId(), event.getMessage());
		pendingExamine.setExamineText(text);

		if (event.getType() == ChatMessageType.ITEM_EXAMINE && config.trackItem()) {
			if (itemExamineLogs.containsKey(pendingExamine.getName())) {
				return;
			}
			itemExamineLogs.put(pendingExamine.getName(), new ItemExamineLog(pendingExamine.getId(), pendingExamine.getExamineText()));
		} else if (event.getType() == ChatMessageType.NPC_EXAMINE && config.trackNPC()) {
			NPCExamineLog npcLog = npcExamineLogs.get(pendingExamine.getName());
			if (npcLog != null) {
				boolean a = npcLog.examineTexts.add(pendingExamine.getExamineText());
				boolean b = npcLog.ids.add(pendingExamine.getId());
				if (!a && !b) {
					return;
				}
			} else {
				npcLog = new NPCExamineLog();
				npcLog.examineTexts.add(pendingExamine.getExamineText());
				npcLog.ids.add(pendingExamine.getId());
				npcExamineLogs.put(pendingExamine.getName(), npcLog);
			}
		} else if (event.getType() == ChatMessageType.OBJECT_EXAMINE && config.trackNPC()) {
			ObjectExamineLog objectLog = objectExamineLogs.get(pendingExamine.getName());
			if (objectLog != null) {
				boolean a = objectLog.examineTexts.add(pendingExamine.getExamineText());
				boolean b = objectLog.ids.add(pendingExamine.getId());
				if (!a && !b) {
					return;
				}
			} else {
				objectLog = new ObjectExamineLog();
				objectLog.examineTexts.add(pendingExamine.getExamineText());
				objectLog.ids.add(pendingExamine.getId());
				objectExamineLogs.put(pendingExamine.getName(), objectLog);
			}
		} else {
			return;
		}

		openPopUp(pendingExamine);
		saveLogs();
	}

	String chatTypeToType(ChatMessageType chatType) {
		switch (chatType)
		{
			case ITEM_EXAMINE:
				return "item";
			case NPC_EXAMINE:
				return "npc";
			case OBJECT_EXAMINE:
				return "object";
			default:
				return "";
		}
	}

	private void openPopUp(PendingExamine pendingExamine) {
		WidgetNode widgetNode = client.openInterface((161 << 16) | 13, 660, WidgetModalMode.MODAL_CLICKTHROUGH);
		client.runScript(3343,
				"Examine Log", String.format("New %s examine:<br><br><col=ffffff>%s</col>",
				chatTypeToType(pendingExamine.getType()), pendingExamine.getExamineText()),
				-1);

		clientThread.invokeLater(() -> {
			Widget w = client.getWidget(660, 1);
			if (w == null || w.getWidth() > 0) {
				return false;
			}
			try {
				client.closeInterface(widgetNode, true);
			} catch (IllegalArgumentException e) {
				log.debug("Interface attempted to close, but was no longer valid.");
			}
			return true;
		});
	}

	@Nullable
	private ObjectComposition getObjectComposition(int id)
	{
		ObjectComposition objectComposition = client.getObjectDefinition(id);
		return objectComposition.getImpostorIds() == null ? objectComposition : objectComposition.getImpostor();
	}

	private TileObject findTileObject(int z, int x, int y, int id)
	{
		Scene scene = client.getScene();
		Tile[][][] tiles = scene.getTiles();
		final Tile tile = tiles[z][x][y];
		if (tile == null)
		{
			return null;
		}

		final GameObject[] tileGameObjects = tile.getGameObjects();
		final DecorativeObject tileDecorativeObject = tile.getDecorativeObject();
		final WallObject tileWallObject = tile.getWallObject();
		final GroundObject groundObject = tile.getGroundObject();

		if (objectIdEquals(tileWallObject, id))
		{
			return tileWallObject;
		}

		if (objectIdEquals(tileDecorativeObject, id))
		{
			return tileDecorativeObject;
		}

		if (objectIdEquals(groundObject, id))
		{
			return groundObject;
		}

		for (GameObject object : tileGameObjects)
		{
			if (objectIdEquals(object, id))
			{
				return object;
			}
		}

		return null;
	}

	private boolean objectIdEquals(TileObject tileObject, int id)
	{
		if (tileObject == null)
		{
			return false;
		}

		if (tileObject.getId() == id)
		{
			return true;
		}

		// Menu action EXAMINE_OBJECT sends the transformed object id, not the base id, unlike
		// all of the GAME_OBJECT_OPTION actions, so check the id against the impostor ids
		final ObjectComposition comp = client.getObjectDefinition(tileObject.getId());

		if (comp.getImpostorIds() != null)
		{
			for (int impostorId : comp.getImpostorIds())
			{
				if (impostorId == id)
				{
					return true;
				}
			}
		}

		return false;
	}
}
