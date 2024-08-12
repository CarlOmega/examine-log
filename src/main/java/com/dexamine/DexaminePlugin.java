package com.dexamine;

import com.google.common.base.Function;
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
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.widgets.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
import net.runelite.http.api.item.ItemType;

import java.util.*;
import java.util.Deque;
import java.util.concurrent.Callable;


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
	private ChatMessageManager chatMessageManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Gson gson;

	private final Deque<PendingExamine> pending = new ArrayDeque<>();

	private Map<String, ItemExamineLog> itemExamineLogs = new HashMap<>();
	private Map<String, NPCExamineLog> npcExamineLogs = new HashMap<>();
	private Map<String, ObjectExamineLog> objectExamineLogs = new HashMap<>();

	private String selected = "";

	final int COMBAT_ACHIEVEMENT_BUTTON = 20;
	final int COLLECTION_LOG_GROUP_ID = 621;
	final int COLLECTION_VIEW = 36;
	final int COLLECTION_VIEW_SCROLLBAR = 37;
	final int COLLECTION_VIEW_HEADER = 19;

	final int COLLECTION_VIEW_CATEGORIES_CONTAINER = 28;
	final int COLLECTION_VIEW_CATEGORIES_RECTANGLE = 33;
	final int COLLECTION_VIEW_CATEGORIES_TEXT = 34;
	final int COLLECTION_VIEW_CATEGORIES_SCROLLBAR = 28;
	final int SELECTED_OPACITY = 200;
	final int UNSELECTED_OPACITY_EVEN = 235;
	final int UNSELECTED_OPACITY_ODD = 255;

	private static final int COLLECTION_LOG_OPEN_OTHER = 2728;
	private static final int COLLECTION_LOG_DRAW_LIST = 2730;
	private static final int COLLECTION_LOG_ITEM_CLICK = 2733;

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
		if (!event.getMenuOption().equals("Examine") || client.getWidget(ComponentID.BANK_CONTAINER) != null)
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
				npcLog.ids.add(pendingExamine.getId());
				if (!npcLog.examineTexts.add(pendingExamine.getExamineText())) {
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

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event) {
		if ((event.getScriptId() == COLLECTION_LOG_OPEN_OTHER || event.getScriptId() == COLLECTION_LOG_ITEM_CLICK ||
				event.getScriptId() == COLLECTION_LOG_DRAW_LIST) && config.enableCustomCollectionLog()) {
			if (config.trackItem() || config.trackNPC() || config.trackObject()) {
				clientThread.invokeLater(this::addExamineWidgets);
			}
		}
	}

	void addExamineWidgets() {
		Widget categoryContainer = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_CATEGORIES_CONTAINER);
		Widget logCategoriesRect = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_CATEGORIES_RECTANGLE);
		Widget logCategoriesText = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_CATEGORIES_TEXT);
		if (logCategoriesRect == null || logCategoriesText == null) {
			return;
		}
		Widget[] categoryRectElements = logCategoriesRect.getDynamicChildren();
		Widget[] categoryTextElements = logCategoriesText.getDynamicChildren();
		if (categoryRectElements.length == 0 || categoryRectElements.length != categoryTextElements.length) {
			return; // The category elements have not been loaded yet.
		}

		unselectAll(false);

		if (config.trackItem() && Arrays.stream(categoryTextElements).map(Widget::getText).noneMatch("Item Examines"::equals)) {
			makeExamineRectWidget("Item Examines", logCategoriesRect, categoryRectElements, this::openItemExamineCategory);
			makeExamineTextWidget("Item Examines", logCategoriesText, categoryTextElements);
			categoryRectElements = logCategoriesRect.getDynamicChildren();
			categoryTextElements = logCategoriesText.getDynamicChildren();
		}

		if (config.trackNPC() && Arrays.stream(categoryTextElements).map(Widget::getText).noneMatch("NPC Examines"::equals)) {
			makeExamineRectWidget("NPC Examines", logCategoriesRect, categoryRectElements, this::openNPCExamineCategory);
			makeExamineTextWidget("NPC Examines", logCategoriesText, categoryTextElements);
			categoryRectElements = logCategoriesRect.getDynamicChildren();
			categoryTextElements = logCategoriesText.getDynamicChildren();
		}

		if (config.trackObject() && Arrays.stream(categoryTextElements).map(Widget::getText).noneMatch("Object Examines"::equals)) {
			makeExamineRectWidget("Object Examines", logCategoriesRect, categoryRectElements, this::openObjectExamineCategory);
			makeExamineTextWidget("Object Examines", logCategoriesText, categoryTextElements);
		}


		int scrollHeight = categoryRectElements.length * 15;
		int newHeight = 0;
		if (categoryContainer.getScrollHeight() > 0 && categoryContainer.getScrollHeight() != scrollHeight)
		{
			newHeight = (categoryContainer.getScrollY() * scrollHeight) / categoryContainer.getScrollHeight();
		}

		categoryContainer.setScrollHeight(scrollHeight);
		categoryContainer.revalidate();
		categoryContainer.revalidateScroll();

		Widget scrollbar = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_CATEGORIES_SCROLLBAR);

		client.runScript(
				ScriptID.UPDATE_SCROLLBAR,
				scrollbar.getId(),
				categoryContainer.getId(),
				newHeight
		);
		logCategoriesText.setHeightMode(0);
		logCategoriesRect.setHeightMode(0);

		logCategoriesText.setOriginalHeight(scrollHeight);
		logCategoriesRect.setOriginalHeight(scrollHeight);

		logCategoriesText.revalidate();
		logCategoriesRect.revalidate();
	}

	private void makeExamineRectWidget(String examineName, Widget logCategories, Widget[] categoryElements, Function<Widget, Boolean> openExamineCategory) {
		int position = categoryElements.length;
		// Last entry as the template
		Widget template = categoryElements[categoryElements.length - 1];
		Widget examineUnlocks = logCategories.createChild(position, template.getType());
		log.debug("pos: {}, name: {}, len: {}", position, examineName, categoryElements.length);
		examineUnlocks.setOpacity(position % 2 == 0 ? UNSELECTED_OPACITY_EVEN : UNSELECTED_OPACITY_ODD);
		examineUnlocks.setName("<col=ff9040>" + examineName + "</col>");
		if (template.hasListener()) {
			examineUnlocks.setHasListener(true);
			examineUnlocks.setAction(1, "View");
			examineUnlocks.setOnOpListener((JavaScriptCallback) e -> {
				openExamineCategory.apply(examineUnlocks);
            });
			examineUnlocks.setOnMouseOverListener((JavaScriptCallback) e -> examineUnlocks.setOpacity(SELECTED_OPACITY));
			examineUnlocks.setOnMouseLeaveListener((JavaScriptCallback) e ->
					examineUnlocks.setOpacity(!selected.equals(examineName)
							? position % 2 == 0 ? UNSELECTED_OPACITY_EVEN : UNSELECTED_OPACITY_ODD
							: SELECTED_OPACITY)
			);
		}
		examineUnlocks.setBorderType(template.getBorderType());
		examineUnlocks.setItemId(template.getItemId());
		examineUnlocks.setSpriteId(template.getSpriteId());
		examineUnlocks.setOriginalHeight(template.getOriginalHeight());
		examineUnlocks.setOriginalWidth((template.getOriginalWidth()));
		examineUnlocks.setOriginalX(template.getOriginalX());
		examineUnlocks.setOriginalY(template.getOriginalY() + template.getOriginalHeight());
		examineUnlocks.setXPositionMode(template.getXPositionMode());
		examineUnlocks.setYPositionMode(template.getYPositionMode());
		examineUnlocks.setContentType(template.getContentType());
		examineUnlocks.setItemQuantity(template.getItemQuantity());
		examineUnlocks.setItemQuantityMode(template.getItemQuantityMode());
		examineUnlocks.setModelId(template.getModelId());
		examineUnlocks.setModelType(template.getModelType());
		examineUnlocks.setBorderType(template.getBorderType());
		examineUnlocks.setFilled(template.isFilled());
		examineUnlocks.setTextColor(template.getTextColor());
		examineUnlocks.setFontId(template.getFontId());
		examineUnlocks.setTextShadowed(template.getTextShadowed());
		examineUnlocks.setWidthMode(template.getWidthMode());
		examineUnlocks.setYTextAlignment(template.getYTextAlignment());
		examineUnlocks.revalidate();
	}

	private void makeExamineTextWidget(String examineName, Widget categories, Widget[] categoryElements) {
		int position = categoryElements.length;
		// Last entry as the template
		Widget template = categoryElements[categoryElements.length - 1];
		Widget examineUnlocks = categories.createChild(position, template.getType());
		examineUnlocks.setText(examineName);
		examineUnlocks.setTextColor(-0x67e1);
		examineUnlocks.setBorderType(template.getBorderType());
		examineUnlocks.setItemId(template.getItemId());
		examineUnlocks.setSpriteId(template.getSpriteId());
		examineUnlocks.setOriginalHeight(template.getOriginalHeight());
		examineUnlocks.setOriginalWidth((template.getOriginalWidth()));
		examineUnlocks.setOriginalX(template.getOriginalX());
		examineUnlocks.setOriginalY(template.getOriginalY() + template.getOriginalHeight());
		examineUnlocks.setXPositionMode(template.getXPositionMode());
		examineUnlocks.setYPositionMode(template.getYPositionMode());
		examineUnlocks.setContentType(template.getContentType());
		examineUnlocks.setItemQuantity(template.getItemQuantity());
		examineUnlocks.setItemQuantityMode(template.getItemQuantityMode());
		examineUnlocks.setModelId(template.getModelId());
		examineUnlocks.setModelType(template.getModelType());
		examineUnlocks.setBorderType(template.getBorderType());
		examineUnlocks.setFilled(template.isFilled());
		examineUnlocks.setFontId(template.getFontId());
		examineUnlocks.setTextShadowed(template.getTextShadowed());
		examineUnlocks.setWidthMode(template.getWidthMode());
		examineUnlocks.setYTextAlignment(template.getYTextAlignment());
		examineUnlocks.revalidate();
	}

	private void unselectAll(boolean clearAll) {
		List<String> examineCategories = List.of("Item Examines", "NPC Examines", "Object Examines");
		Widget logCategoriesRect = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_CATEGORIES_RECTANGLE);
		if (logCategoriesRect == null) {
			return;
		}
		Widget[] categoryRectElements = logCategoriesRect.getDynamicChildren();
		if (categoryRectElements.length == 0) {
			return; // The category elements have not been loaded yet.
		}

		if (clearAll) {
			selected = "";
		}

		for (int i = 0; i < categoryRectElements.length; i++) {
			if (categoryRectElements[i].getOpacity() == SELECTED_OPACITY) {
				String title = Text.removeTags(categoryRectElements[i].getName());
				if (clearAll || examineCategories.contains(title)) {
					categoryRectElements[i].setOpacity(i % 2 == 0 ? UNSELECTED_OPACITY_EVEN : UNSELECTED_OPACITY_ODD);
				}
			}
		}
	}

	private Boolean openItemExamineCategory(Widget itemExamineWidget) {
		String examineName = "Item Examines";
		unselectAll(true);
		itemExamineWidget.setOpacity(SELECTED_OPACITY);
		selected = examineName;

		clientThread.invokeLater(() -> {
			Widget collectionViewHeader = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_HEADER);
			Widget combatAchievementsButton = client.getWidget(COLLECTION_LOG_GROUP_ID, COMBAT_ACHIEVEMENT_BUTTON);
			combatAchievementsButton.setHidden(true);
			Widget[] headerComponents = collectionViewHeader.getDynamicChildren();
			headerComponents[0].setText(examineName);
			headerComponents[1].setText("Examines: <col=ffff00>" + itemExamineLogs.values().size() + "/???");
			if (headerComponents.length > 2) {
				headerComponents[1].setText("");
				headerComponents[2].setText("Examines: <col=ffff00>" + itemExamineLogs.values().size() + "/???");
			}

			Widget collectionView = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW);
			Widget scrollbar = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_SCROLLBAR);
			collectionView.deleteAllChildren();

			int index = 0;
			int x = 0;
			int y = 0;
			int yIncrement = 40;
			int xIncrement = 42;
			for (ItemExamineLog itemExamineLog : itemExamineLogs.values()) {
				addItemToCollectionLog(collectionView, itemExamineLog.getId(), itemExamineLog.getExamineText(), x, y, index);
				x = x + xIncrement;
				index++;
				if (x > 210) {
					x = 0;
					y = y + yIncrement;
				}
			}

			collectionView.setScrollHeight(y + 43);  // y + image height (40) + 3 for padding at the bottom.
			int scrollHeight = (collectionView.getScrollY() * y) / collectionView.getScrollHeight();
			collectionView.revalidateScroll();
			client.runScript(ScriptID.UPDATE_SCROLLBAR, scrollbar.getId(), collectionView.getId(), scrollHeight);
			collectionView.setScrollY(0);
			scrollbar.setScrollY(0);
		});

		return true;

	}

	private Boolean openNPCExamineCategory(Widget npcExamineWidget) {
		String examineName = "NPC Examines";
		unselectAll(true);
		npcExamineWidget.setOpacity(SELECTED_OPACITY);
		selected = examineName;

		clientThread.invokeLater(() -> {
			Widget collectionViewHeader = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_HEADER);
			Widget combatAchievementsButton = client.getWidget(COLLECTION_LOG_GROUP_ID, COMBAT_ACHIEVEMENT_BUTTON);
			combatAchievementsButton.setHidden(true);
			Widget[] headerComponents = collectionViewHeader.getDynamicChildren();
			headerComponents[0].setText(examineName);
			headerComponents[1].setText("Examines: <col=ffff00>" + npcExamineLogs.values().size() + "/???");
			if (headerComponents.length > 2) {
				headerComponents[1].setText("");
				headerComponents[2].setText("Examines: <col=ffff00>" + npcExamineLogs.values().size() + "/???");
			}

			Widget collectionView = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW);
			Widget scrollbar = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_SCROLLBAR);
			collectionView.deleteAllChildren();

			int index = 0;
			int x = 0;
			int y = 0;
			int yIncrement = 40;
			int xIncrement = 42;
			for (String key : npcExamineLogs.keySet()) {
				NPCExamineLog npcExamineLog = npcExamineLogs.get(key);
				String[] examineTexts = npcExamineLog.examineTexts.toArray(String[]::new);
				addEntryToCollectionLog(collectionView, ItemID.FAKE_MAN, key, examineTexts, x, y, index);
				x = x + xIncrement;
				index++;
				if (x > 210) {
					x = 0;
					y = y + yIncrement;
				}
			}

			collectionView.setScrollHeight(y + 43);  // y + image height (40) + 3 for padding at the bottom.
			int scrollHeight = (collectionView.getScrollY() * y) / collectionView.getScrollHeight();
			collectionView.revalidateScroll();
			client.runScript(ScriptID.UPDATE_SCROLLBAR, scrollbar.getId(), collectionView.getId(), scrollHeight);
			collectionView.setScrollY(0);
			scrollbar.setScrollY(0);
		});

		return true;
	}

	private Boolean openObjectExamineCategory(Widget objectExamineWidget) {
		String examineName = "Object Examines";
		unselectAll(true);
		objectExamineWidget.setOpacity(SELECTED_OPACITY);
		selected = examineName;

		clientThread.invokeLater(() -> {
			Widget collectionViewHeader = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_HEADER);
			Widget combatAchievementsButton = client.getWidget(COLLECTION_LOG_GROUP_ID, COMBAT_ACHIEVEMENT_BUTTON);
			combatAchievementsButton.setHidden(true);
			Widget[] headerComponents = collectionViewHeader.getDynamicChildren();
			headerComponents[0].setText(examineName);
			headerComponents[1].setText("Examines: <col=ffff00>" + objectExamineLogs.values().size() + "/???");
			if (headerComponents.length > 2) {
				headerComponents[1].setText("");
				headerComponents[2].setText("Examines: <col=ffff00>" + objectExamineLogs.values().size() + "/???");
			}

			Widget collectionView = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW);
			Widget scrollbar = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_SCROLLBAR);
			collectionView.deleteAllChildren();

			int index = 0;
			int x = 0;
			int y = 0;
			int yIncrement = 40;
			int xIncrement = 42;
			for (String key : objectExamineLogs.keySet()) {
				ObjectExamineLog objectExamineLog = objectExamineLogs.get(key);
				String[] examineTexts = objectExamineLog.examineTexts.toArray(String[]::new);
				addEntryToCollectionLog(collectionView, ItemID.WOODEN_CHAIR, key, examineTexts, x, y, index);
				x = x + xIncrement;
				index++;
				if (x > 210) {
					x = 0;
					y = y + yIncrement;
				}
			}

			collectionView.setScrollHeight(y + 43);  // y + image height (40) + 3 for padding at the bottom.
			int scrollHeight = (collectionView.getScrollY() * y) / collectionView.getScrollHeight();
			collectionView.revalidateScroll();
			client.runScript(ScriptID.UPDATE_SCROLLBAR, scrollbar.getId(), collectionView.getId(), scrollHeight);
			collectionView.setScrollY(0);
			scrollbar.setScrollY(0);
		});

		return true;
	}

	private void addItemToCollectionLog(Widget collectionView, Integer itemId, String examineText, int x, int y, int index) {
		String itemName = itemManager.getItemComposition(itemId).getName();
		Widget newItem = collectionView.createChild(index, 5);
		newItem.setContentType(0);
		newItem.setItemId(itemId);
		newItem.setItemQuantity(1);
		newItem.setItemQuantityMode(0);
		newItem.setModelId(-1);
		newItem.setModelType(1);
		newItem.setSpriteId(-1);
		newItem.setBorderType(1);
		newItem.setFilled(false);
		newItem.setOriginalX(x);
		newItem.setOriginalY(y);
		newItem.setOriginalWidth(36);
		newItem.setOriginalHeight(32);
		newItem.setHasListener(true);
		newItem.setAction(1, "Inspect");
		newItem.setOnOpListener((JavaScriptCallback) e -> handleItemAction(itemName, examineText, e));
		newItem.setName(itemName);
		newItem.revalidate();
	}

	private void addEntryToCollectionLog(Widget collectionView, Integer itemID, String name, String[] examineTexts, int x, int y, int index) {
		Widget newItem = collectionView.createChild(index, 5);
		newItem.setContentType(0);
		newItem.setItemId(itemID);
		newItem.setItemQuantity(1);
		newItem.setItemQuantityMode(0);
		newItem.setModelId(-1);
		newItem.setModelType(1);
		newItem.setSpriteId(-1);
		newItem.setBorderType(1);
		newItem.setFilled(false);
		newItem.setOriginalX(x);
		newItem.setOriginalY(y);
		newItem.setOriginalWidth(36);
		newItem.setOriginalHeight(32);
		newItem.setHasListener(true);
		newItem.setAction(1, "Inspect");
		newItem.setOnOpListener((JavaScriptCallback) e -> handleEntryAction(name, examineTexts, e));
		newItem.setName(name);
		newItem.revalidate();
	}

	private void handleItemAction(String name, String examineText, ScriptEvent event) {
        if (event.getOp() == 2) {
            final ChatMessageBuilder examination = new ChatMessageBuilder()
					.append(ChatColorType.HIGHLIGHT)
					.append("Examines for " + name + ": ")
                    .append(ChatColorType.NORMAL)
                    .append(examineText);

            chatMessageManager.queue(QueuedMessage.builder()
							.type(ChatMessageType.CONSOLE)
							.runeLiteFormattedMessage(examination.build())
							.build());
        }
	}

	private void handleEntryAction(String name, String[] examineTexts, ScriptEvent event) {
		if (event.getOp() == 2) {
			int i = 1;
			final ChatMessageBuilder title = new ChatMessageBuilder()
					.append(ChatColorType.HIGHLIGHT)
					.append("Examines for " + name + ":");

			chatMessageManager.queue(QueuedMessage.builder()
					.type(ChatMessageType.CONSOLE)
					.runeLiteFormattedMessage(title.build())
					.build());

			for (String examineText: examineTexts) {
				final ChatMessageBuilder examination = new ChatMessageBuilder()
						.append(ChatColorType.HIGHLIGHT)
						.append(i++ + ". ")
						.append(ChatColorType.NORMAL)
						.append(examineText);

				chatMessageManager.queue(QueuedMessage.builder()
						.type(ChatMessageType.CONSOLE)
						.runeLiteFormattedMessage(examination.build())
						.build());
			}
		}
	}



}
