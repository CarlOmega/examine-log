package com.dexamine;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.widgets.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;

import static net.runelite.client.RuneLite.RUNELITE_DIR;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.awt.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Deque;
import java.util.*;
import java.util.List;
import java.util.function.Function;


@Slf4j
@PluginDescriptor(
        name = "Examine Log (Dexamine)"
)
public class DexaminePlugin extends Plugin {
    private static final String ITEM_LOGS = "item-logs";
    private static final String NPC_LOGS = "npc-logs";
    private static final String OBJECT_LOGS = "object-logs";
    private static final int COLLECTION_LOG_OPEN_OTHER = 2728;
    private static final int COLLECTION_LOG_DRAW_LIST = 2730;
    private static final int COLLECTION_LOG_ITEM_CLICK = 2733;
    final int COMBAT_ACHIEVEMENT_BUTTON = 20;
    final int COLLECTION_LOG_POPUP_WIDGET = 660;
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
    private final Deque<PendingExamine> pending = new ArrayDeque<>();
    private final File EXAMINE_LOG_DIR = new File(RUNELITE_DIR, "examine-log");
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
    private File playerFolder = null;
    private Map<String, BaseExamineLog> itemExamineLogs = new HashMap<>();
    private Map<String, NPCExamineLog> npcExamineLogs = new HashMap<>();
    private Map<String, ObjectExamineLog> objectExamineLogs = new HashMap<>();
    private String selected = "";

    @Override
    protected void startUp() throws Exception {
        log.info("Dexamine started!");
        EXAMINE_LOG_DIR.mkdirs();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged) {
        switch (gameStateChanged.getGameState()) {
            case LOGGED_IN:
                loadExamineLogsFromDisk();
                break;
            case LOGIN_SCREEN:
            case HOPPING: {
                playerFolder = null;
                itemExamineLogs = new HashMap<>();
                npcExamineLogs = new HashMap<>();
                objectExamineLogs = new HashMap<>();
            }
        }
    }

    @Subscribe
    public void onClientShutdown(ClientShutdown event) {
        saveLogs();
    }

    public Path getLogFilePath(String examineLogName) {
        File examineLogFile = new File(this.playerFolder, examineLogName + ".json");
        return examineLogFile.toPath();
    }

    private File getPlayerFolder(String playerDir) {
        RuneScapeProfileType profileType = RuneScapeProfileType.getCurrent(client);
        if (profileType != RuneScapeProfileType.STANDARD) {
            playerDir = playerDir + "-" + Text.titleCase(profileType);
        }
        File playerFolder = new File(EXAMINE_LOG_DIR, playerDir);
        playerFolder.mkdirs();
        return playerFolder;
    }

    public void writeLogsToDisk(String examineLogName, String examineLogs) {
        if (this.playerFolder == null) {
            return;
        }
        Path filePath = getLogFilePath(examineLogName);
        try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
            writer.write(examineLogs);
        } catch (IOException e) {
            log.error("Unable to write examine logs to: " + filePath, e);
        }
    }

    public void loadExamineLogsFromDisk() {
        if (this.playerFolder != null) {
            return;
        }

        String profileKey = configManager.getRSProfileKey();

        this.playerFolder = getPlayerFolder(profileKey);
        log.debug("Loading logs for profile: {}", this.playerFolder.getName());

        final Path itemLogsPath = getLogFilePath(ITEM_LOGS);
        if (Files.exists(itemLogsPath)) {
            try (BufferedReader reader = Files.newBufferedReader(itemLogsPath);
                 JsonReader jsonReader = new JsonReader(reader)) {
                itemExamineLogs = gson.fromJson(jsonReader, new TypeToken<Map<String, BaseExamineLog>>() {
                }.getType());
            } catch (IOException | JsonSyntaxException e) {
                log.error("Unable to read item logs at: " + itemLogsPath, e);
            }
        }

        final Path npcExamineLogsPath = getLogFilePath(NPC_LOGS);
        if (Files.exists(npcExamineLogsPath)) {
            try (BufferedReader reader = Files.newBufferedReader(npcExamineLogsPath);
                 JsonReader jsonReader = new JsonReader(reader)) {
                npcExamineLogs = gson.fromJson(jsonReader, new TypeToken<Map<String, NPCExamineLog>>() {
                }.getType());
            } catch (IOException | JsonSyntaxException e) {
                log.error("Unable to read npc logs at: " + itemLogsPath, e);
            }
        }

        final Path objectExamineLogsPath = getLogFilePath(OBJECT_LOGS);
        if (Files.exists(objectExamineLogsPath)) {
            try (BufferedReader reader = Files.newBufferedReader(objectExamineLogsPath);
                 JsonReader jsonReader = new JsonReader(reader)) {
                objectExamineLogs = gson.fromJson(jsonReader, new TypeToken<Map<String, ObjectExamineLog>>() {
                }.getType());
            } catch (IOException | JsonSyntaxException e) {
                log.error("Unable to read object logs at: " + itemLogsPath, e);
            }
        }
    }

    @Override
    protected void shutDown() throws Exception {
        log.info("Dexamine stopped!");
        saveLogs();
    }

    void saveLogs() {
        if (config.trackItem() && !itemExamineLogs.isEmpty()) {
            writeLogsToDisk(ITEM_LOGS, gson.toJson(itemExamineLogs));
        }
        if (config.trackNPC() && !npcExamineLogs.isEmpty()) {
            writeLogsToDisk(NPC_LOGS, gson.toJson(npcExamineLogs));
        }
        if (config.trackObject() && !objectExamineLogs.isEmpty()) {
            writeLogsToDisk(OBJECT_LOGS, gson.toJson(objectExamineLogs));
        }
    }

    @Provides
    DexamineConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(DexamineConfig.class);
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (!event.getMenuOption().equals("Examine")) {
            return;
        }

        ChatMessageType type;
        MenuEntry entry = event.getMenuEntry();
        int id;
        String name;
        switch (event.getMenuAction()) {
            case EXAMINE_ITEM_GROUND: {
                type = ChatMessageType.ITEM_EXAMINE;
                final ItemComposition itemComposition = itemManager.getItemComposition(event.getId());
                id = event.getId();
                name = itemComposition.getName();
                // treat all banknotes as the same item
                if (itemComposition.getNote() != -1) {
                    id = 799;
                    name = "Bank note";
                }
                break;
            }
            case CC_OP_LOW_PRIORITY: {
                // Only count items in players inventory otherwise can abuse with multiple other widgets
                Widget widget = event.getWidget();
                if (widget == null || widget.getParent().getId() != ComponentID.INVENTORY_CONTAINER) {
                    return;
                }
                type = ChatMessageType.ITEM_EXAMINE;
                final ItemComposition itemComposition = itemManager.getItemComposition(event.getItemId());
                id = event.getItemId();
                name = itemComposition.getName();
                // treat all banknotes as the same item
                if (itemComposition.getNote() != -1) {
                    id = 799;
                    name = "Bank note";
                }
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
    public void onChatMessage(ChatMessage event) {
        PendingExamine pendingExamine;
        if (!pending.isEmpty()) {
            pendingExamine = pending.poll();
        } else {
            return;
        }

        String text = Text.removeTags(event.getMessage());

        switch (event.getType()) {
            case ITEM_EXAMINE:
                if (text.startsWith("Price of ")) {
                    return;
                }
            case OBJECT_EXAMINE:
            case NPC_EXAMINE:
                if (pendingExamine.getType() != event.getType()) {
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

        loadExamineLogsFromDisk();

        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        WorldPoint playerPos = client.getLocalPlayer().getWorldLocation();
        BaseExamineLog examineLog = new BaseExamineLog(pendingExamine.getId(), pendingExamine.getExamineText(), timestamp, playerPos);

        if (event.getType() == ChatMessageType.ITEM_EXAMINE && config.trackItem()) {
            if (itemExamineLogs.containsKey(pendingExamine.getName())) {
                return;
            }
            itemExamineLogs.put(pendingExamine.getName(), examineLog);
        } else if (event.getType() == ChatMessageType.NPC_EXAMINE && config.trackNPC()) {
            NPCExamineLog npcLog = npcExamineLogs.get(pendingExamine.getName());
            if (npcLog != null) {
                npcLog.ids.add(pendingExamine.getId());
                if (npcLog.examineLogs.containsKey(pendingExamine.getExamineText())) {
                    return;
                }
                npcLog.examineLogs.put(pendingExamine.getExamineText(), examineLog);
            } else {
                npcLog = new NPCExamineLog();
                npcLog.ids.add(pendingExamine.getId());
                npcLog.examineLogs.put(pendingExamine.getExamineText(), examineLog);
                npcExamineLogs.put(pendingExamine.getName(), npcLog);
            }
        } else if (event.getType() == ChatMessageType.OBJECT_EXAMINE && config.trackObject()) {
            ObjectExamineLog objectLog = objectExamineLogs.get(pendingExamine.getName());
            if (objectLog != null) {
                objectLog.ids.add(pendingExamine.getId());
                if (objectLog.examineLogs.containsKey(pendingExamine.getExamineText())) {
                    return;
                }
                objectLog.examineLogs.put(pendingExamine.getExamineText(), examineLog);
            } else {
                objectLog = new ObjectExamineLog();
                objectLog.ids.add(pendingExamine.getId());
                objectLog.examineLogs.put(pendingExamine.getExamineText(), examineLog);
                objectExamineLogs.put(pendingExamine.getName(), objectLog);
            }
        } else {
            return;
        }

        openPopUp(pendingExamine);
        saveLogs();
    }

    String chatTypeToType(ChatMessageType chatType) {
        switch (chatType) {
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
        if (!config.enableCollectionLogPopup()) {
            return;
        }

        // Handles both resizable and fixed modes now.
        int componentId = (client.getTopLevelInterfaceId() << 16) | (client.isResized() ? 13 : 43);
        WidgetNode widgetNode = client.openInterface(componentId, COLLECTION_LOG_POPUP_WIDGET, WidgetModalMode.MODAL_CLICKTHROUGH);
        client.runScript(3343,
                "Examine Log", String.format("New %s examine:<br><br><col=ffffff>%s</col>",
                        chatTypeToType(pendingExamine.getType()), pendingExamine.getExamineText()),
                -1);

        clientThread.invokeLater(() -> {
            Widget w = client.getWidget(COLLECTION_LOG_POPUP_WIDGET, 1);
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
    private ObjectComposition getObjectComposition(int id) {
        ObjectComposition objectComposition = client.getObjectDefinition(id);
        return objectComposition.getImpostorIds() == null ? objectComposition : objectComposition.getImpostor();
    }

    private TileObject findTileObject(int z, int x, int y, int id) {
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        final Tile tile = tiles[z][x][y];
        if (tile == null) {
            return null;
        }

        final GameObject[] tileGameObjects = tile.getGameObjects();
        final DecorativeObject tileDecorativeObject = tile.getDecorativeObject();
        final WallObject tileWallObject = tile.getWallObject();
        final GroundObject groundObject = tile.getGroundObject();

        if (objectIdEquals(tileWallObject, id)) {
            return tileWallObject;
        }

        if (objectIdEquals(tileDecorativeObject, id)) {
            return tileDecorativeObject;
        }

        if (objectIdEquals(groundObject, id)) {
            return groundObject;
        }

        for (GameObject object : tileGameObjects) {
            if (objectIdEquals(object, id)) {
                return object;
            }
        }

        return null;
    }

    private boolean objectIdEquals(TileObject tileObject, int id) {
        if (tileObject == null) {
            return false;
        }

        if (tileObject.getId() == id) {
            return true;
        }

        final ObjectComposition comp = client.getObjectDefinition(tileObject.getId());

        if (comp.getImpostorIds() != null) {
            for (int impostorId : comp.getImpostorIds()) {
                if (impostorId == id) {
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
        if (logCategoriesRect == null || logCategoriesText == null || categoryContainer == null) {
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
        int currentScrollHeight = categoryContainer.getScrollHeight();
        if (currentScrollHeight > 0 && categoryContainer.getScrollHeight() != scrollHeight) {
            newHeight = (categoryContainer.getScrollY() * scrollHeight) / categoryContainer.getScrollHeight();
        }

        categoryContainer.setScrollHeight(scrollHeight);
        categoryContainer.revalidate();
        categoryContainer.revalidateScroll();

        Widget scrollbar = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_CATEGORIES_SCROLLBAR);
        if (scrollbar == null) {
            return;
        }
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
            Widget collectionView = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW);
            Widget scrollbar = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_SCROLLBAR);
            if (collectionViewHeader == null || collectionView == null) {
                return;
            }
            if (combatAchievementsButton != null) {
                combatAchievementsButton.setHidden(true);
            }

            Widget[] headerComponents = collectionViewHeader.getDynamicChildren();
            headerComponents[0].setText(examineName);
            headerComponents[1].setText("Examines: <col=ffff00>" + itemExamineLogs.values().size() + "/???");
            // Some collection logs have more than two titles this is just to clean up
            for (int i = 2; i < headerComponents.length; i++) {
                headerComponents[i].setText("");
            }

            collectionView.deleteAllChildren();
            int index = 0;
            int x = 0;
            int y = 0;
            int yIncrement = 40; // sprite height
            int xIncrement = 42; // sprite width
            for (BaseExamineLog itemExamineLog : itemExamineLogs.values()) {
                addItemToCollectionLog(collectionView, itemExamineLog.getId(), itemExamineLog.getExamineText(), x, y, index);
                x = x + xIncrement;
                index++;
                if (x > 210) {
                    x = 0;
                    y = y + yIncrement;
                }
            }
            if (scrollbar != null) {
                collectionView.setScrollHeight(y + 40 + 3); // 3 padding
                int scrollHeight = (collectionView.getScrollY() * y) / collectionView.getScrollHeight();
                collectionView.revalidateScroll();
                client.runScript(ScriptID.UPDATE_SCROLLBAR, scrollbar.getId(), collectionView.getId(), scrollHeight);
                collectionView.setScrollY(0);
                scrollbar.setScrollY(0);
            }
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
            Widget collectionView = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW);
            Widget scrollbar = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_SCROLLBAR);
            if (collectionViewHeader == null || collectionView == null) {
                return;
            }
            if (combatAchievementsButton != null) {
                combatAchievementsButton.setHidden(true);
            }

            Widget[] headerComponents = collectionViewHeader.getDynamicChildren();
            headerComponents[0].setText(examineName);
            headerComponents[1].setText("Examines: <col=ffff00>" + npcExamineLogs.values().size() + "/???");
            // Some collection logs have more than two titles this is just to clean up
            for (int i = 2; i < headerComponents.length; i++) {
                headerComponents[i].setText("");
            }

            collectionView.deleteAllChildren();

            int index = 0;
            int x = 0;
            int y = 0;
            int yIncrement = 40;
            int xIncrement = 42;
            for (String key : npcExamineLogs.keySet()) {
                NPCExamineLog npcExamineLog = npcExamineLogs.get(key);
                String[] examineTexts = npcExamineLog.examineLogs.keySet().toArray(String[]::new);
                addEntryToCollectionLog(collectionView, ItemID.FAKE_MAN, key, examineTexts, x, y, index);
                x = x + xIncrement;
                index++;
                if (x > 210) {
                    x = 0;
                    y = y + yIncrement;
                }
            }

            if (scrollbar != null) {
                collectionView.setScrollHeight(y + 40 + 3); // 3 padding
                int scrollHeight = (collectionView.getScrollY() * y) / collectionView.getScrollHeight();
                collectionView.revalidateScroll();
                client.runScript(ScriptID.UPDATE_SCROLLBAR, scrollbar.getId(), collectionView.getId(), scrollHeight);
                collectionView.setScrollY(0);
                scrollbar.setScrollY(0);
            }
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
            Widget collectionView = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW);
            Widget scrollbar = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_SCROLLBAR);
            if (collectionViewHeader == null || collectionView == null) {
                return;
            }
            if (combatAchievementsButton != null) {
                combatAchievementsButton.setHidden(true);
            }
            Widget[] headerComponents = collectionViewHeader.getDynamicChildren();
            headerComponents[0].setText(examineName);
            headerComponents[1].setText("Examines: <col=ffff00>" + objectExamineLogs.values().size() + "/???");
            // Some collection logs have more than two titles this is just to clean up
            for (int i = 2; i < headerComponents.length; i++) {
                headerComponents[i].setText("");
            }

            collectionView.deleteAllChildren();

            int index = 0;
            int x = 0;
            int y = 0;
            int yIncrement = 40;
            int xIncrement = 42;
            for (String key : objectExamineLogs.keySet()) {
                ObjectExamineLog objectExamineLog = objectExamineLogs.get(key);
                String[] examineTexts = objectExamineLog.examineLogs.keySet().toArray(String[]::new);
                addEntryToCollectionLog(collectionView, ItemID.WOODEN_CHAIR, key, examineTexts, x, y, index);
                x = x + xIncrement;
                index++;
                if (x > 210) {
                    x = 0;
                    y = y + yIncrement;
                }
            }

            if (scrollbar != null) {
                collectionView.setScrollHeight(y + 40 + 3); // 3 padding
                int scrollHeight = (collectionView.getScrollY() * y) / collectionView.getScrollHeight();
                collectionView.revalidateScroll();
                client.runScript(ScriptID.UPDATE_SCROLLBAR, scrollbar.getId(), collectionView.getId(), scrollHeight);
                collectionView.setScrollY(0);
                scrollbar.setScrollY(0);
            }
        });

        return true;
    }

    private void addItemToCollectionLog(Widget collectionView, Integer itemId, String examineText, int x, int y, int index) {
        String itemName = itemId == 799 ? "Bank note" : itemManager.getItemComposition(itemId).getName();
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
            final ChatMessageBuilder title = new ChatMessageBuilder()
                    .append(ChatColorType.HIGHLIGHT)
                    .append("Examine log")
                    .append(ChatColorType.NORMAL)
                    .append(" [")
                    .append(new Color(0, 0, 255), name)
                    .append("]: ");

            chatMessageManager.queue(QueuedMessage.builder()
                    .type(ChatMessageType.CONSOLE)
                    .runeLiteFormattedMessage(title.build())
                    .build());

            final ChatMessageBuilder examination = new ChatMessageBuilder()
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
                    .append("Examine log")
                    .append(ChatColorType.NORMAL)
                    .append(" [")
                    .append(new Color(0, 0, 255), name)
                    .append("]: ");

            chatMessageManager.queue(QueuedMessage.builder()
                    .type(ChatMessageType.CONSOLE)
                    .runeLiteFormattedMessage(title.build())
                    .build());

            for (String examineText : examineTexts) {
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
