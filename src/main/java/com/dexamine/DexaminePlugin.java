package com.dexamine;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.widgets.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

import static net.runelite.client.RuneLite.RUNELITE_DIR;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Deque;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;


@Slf4j
@PluginDescriptor(
        name = "Examine Log"
)
public class DexaminePlugin extends Plugin {
    private static final String ITEM_LOGS = "item-logs";
    private static final String NPC_LOGS = "npc-logs";
    private static final String OBJECT_LOGS = "object-logs";
    final int COLLECTION_LOG_POPUP_WIDGET = 660;
    final int SKILL_GUIDE_WIDGET = 860;
    final int BANK_NOTE_ITEM_ID = 799;
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
    private Map<String, String> fullItemExamineLogs = new HashMap<>();
    private Map<String, List<String>> fullNpcExamineLogs = new HashMap<>();
    private Map<String, List<String>> fullObjectExamineLogs = new HashMap<>();
    private WidgetNode examineLogWidgetNode = null;
    private String openSkillGuideInterfaceSource = "";
    private String selectedTab = "items";

    @Override
    protected void startUp() throws Exception {
        log.info("Dexamine started!");
        EXAMINE_LOG_DIR.mkdirs();
        loadFullExamineList();
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

    private void loadFullExamineList() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("item_map.json")) {
            final InputStreamReader data = new InputStreamReader(in, StandardCharsets.UTF_8);
            final Type type = new TypeToken<Map<String, String>>() {
            }.getType();
            fullItemExamineLogs = gson.fromJson(data, type);
        } catch (IOException | JsonSyntaxException e) {
            log.error("Unable to read full items logs at: item_map.json", e);
        }
        try (InputStream in = getClass().getResourceAsStream("npc_map.json")) {
            final InputStreamReader data = new InputStreamReader(in, StandardCharsets.UTF_8);
            final Type type = new TypeToken<Map<String, List<String>>>() {
            }.getType();
            fullNpcExamineLogs = gson.fromJson(data, type);
        } catch (IOException | JsonSyntaxException e) {
            log.error("Unable to read full npc logs at npc_map.json", e);
        }
        try (InputStream in = getClass().getResourceAsStream("object_map.json")) {
            final InputStreamReader data = new InputStreamReader(in, StandardCharsets.UTF_8);
            final Type type = new TypeToken<Map<String, List<String>>>() {
            }.getType();
            fullObjectExamineLogs = gson.fromJson(data, type);
        } catch (IOException | JsonSyntaxException e) {
            log.error("Unable to read full object logs at object_map.json ", e);
        }
    }

    @Override
    protected void shutDown() throws Exception {
        log.info("Dexamine stopped!");
        saveLogs();
    }

    void saveLogs() {
        if (!itemExamineLogs.isEmpty()) {
            writeLogsToDisk(ITEM_LOGS, gson.toJson(itemExamineLogs));
        }
        if (!npcExamineLogs.isEmpty()) {
            writeLogsToDisk(NPC_LOGS, gson.toJson(npcExamineLogs));
        }
        if (!objectExamineLogs.isEmpty()) {
            writeLogsToDisk(OBJECT_LOGS, gson.toJson(objectExamineLogs));
        }
    }

    @Provides
    DexamineConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(DexamineConfig.class);
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (!event.getMenuOption().startsWith("Examine")) {
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
                    id = BANK_NOTE_ITEM_ID;
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
                    id = BANK_NOTE_ITEM_ID;
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
                TileObject object = findTileObject(
                    client.getPlane(), entry.getParam0(), entry.getParam1(), entry.getIdentifier()
                );
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
        BaseExamineLog examineLog = new BaseExamineLog(
            pendingExamine.getId(),
            pendingExamine.getExamineText(),
            timestamp,
            playerPos
        );

        if (event.getType() == ChatMessageType.ITEM_EXAMINE) {
            if (itemExamineLogs.containsKey(pendingExamine.getName())) {
                return;
            }
            itemExamineLogs.put(pendingExamine.getName(), examineLog);
        } else if (event.getType() == ChatMessageType.NPC_EXAMINE) {
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
        } else if (event.getType() == ChatMessageType.OBJECT_EXAMINE) {
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

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event) {
        if (config.enableCustomCollectionLog()
            && event.getMenuEntry().getWidget() != null
            && event.getMenuEntry().getWidget().getParentId() == ComponentID.CHARACTER_SUMMARY_CONTAINER + 1
            && Objects.equals(event.getMenuEntry().getOption(), "Collection Log")
        ) {
            client.createMenuEntry(-1)
                    .setOption("Examine Log")
                    .setTarget(event.getTarget())
                    .setIdentifier(event.getIdentifier())
                    .setType(MenuAction.RUNELITE)
                    .onClick(this::openExamineLog);
        }

        if (config.enableMenuHints()
            && event.getMenuEntry() != null
            && event.getMenuEntry().getOption().equals("Examine")
        ) {
            MenuEntry entry = event.getMenuEntry();
            switch (entry.getType()) {
                case EXAMINE_ITEM_GROUND: {
                    final ItemComposition itemComposition = itemManager.getItemComposition(event.getIdentifier());
                    if (itemComposition.getNote() != -1) {
                        if (itemExamineLogs.get("Bank note") != null) {
                            return;
                        }
                    } else if (itemExamineLogs.get(itemComposition.getName()) != null) {
                        return;
                    }
                    break;
                }
                case CC_OP_LOW_PRIORITY: {
                    Widget widget = entry.getWidget();
                    if (widget == null || widget.getParent().getId() != ComponentID.INVENTORY_CONTAINER) {
                        return;
                    }
                    final ItemComposition itemComposition = itemManager.getItemComposition(event.getItemId());
                    if (itemComposition.getNote() != -1) {
                        if (itemExamineLogs.get("Bank note") != null) {
                            return;
                        }
                    } else if (itemExamineLogs.get(itemComposition.getName()) != null) {
                        return;
                    }
                    break;
                }
                case EXAMINE_NPC: {
                    NPC npc = entry.getNpc();
                    if (npc == null || npcExamineLogs.get(npc.getName()) != null) {
                        return;
                    }
                    break;
                }
                case EXAMINE_OBJECT: {
                    TileObject object = findTileObject(
                        client.getPlane(),
                        entry.getParam0(),
                        entry.getParam1(),
                        entry.getIdentifier()
                    );
                    if (object == null) {
                        return;
                    }
                    ObjectComposition objectDefinition = getObjectComposition(object.getId());
                    if (objectDefinition == null || objectExamineLogs.get(objectDefinition.getName()) != null) {
                        return;
                    }
                    break;
                }
            }
            entry.setOption("Examine*");
        }
    }

    private void openExamineLog(MenuEntry menuEntry) {
        clientThread.invokeLater(() -> {
            // Handles both resizable and fixed modes
            int componentId = (client.getTopLevelInterfaceId() << 16) | (client.isResized() ? 18 : 42);
            this.examineLogWidgetNode = client.openInterface(
                componentId, SKILL_GUIDE_WIDGET,
                WidgetModalMode.MODAL_NOCLICKTHROUGH
            );
            this.openSkillGuideInterfaceSource = "characterSummary";
            this.selectedTab = "items";
            client.runScript(1902, 1, 0);
        });
    }

    private void openPopUp(PendingExamine pendingExamine) {
        if (!config.enableCollectionLogPopup()) {
            return;
        }
        clientThread.invokeLater(() -> {
            // Handles both resizable and fixed modes
            int componentId = (client.getTopLevelInterfaceId() << 16) | (client.isResized() ? 13 : 43);
            WidgetNode widgetNode = client.openInterface(
                componentId,
                COLLECTION_LOG_POPUP_WIDGET,
                WidgetModalMode.MODAL_CLICKTHROUGH
            );
            String title = "Examine Log";
            String description = String.format(
                "New %s examine:<br><br><col=ffffff>%s</col>",
                chatTypeToType(pendingExamine.getType()),
                pendingExamine.getExamineText()
            );
            client.runScript(3343, title, description, -1);

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
    public void onScriptPreFired(ScriptPreFired event) {
        // Catch the close interface failsafe if skill summary is closed normally while examine log is open
        if (event.getScriptId() == 489 && this.examineLogWidgetNode != null) {
            clientThread.invokeLater(() -> {
                client.closeInterface(this.examineLogWidgetNode, true);
                this.examineLogWidgetNode = null;
                this.openSkillGuideInterfaceSource = "";
                return true;
            });
        }
        // Checks if the open source was from somewhere unknown and resets log to not render
        if (event.getScriptId() == 1902 && this.examineLogWidgetNode != null) {
            if (this.openSkillGuideInterfaceSource.isEmpty()) {
                this.examineLogWidgetNode = null;
            } else {
                this.openSkillGuideInterfaceSource = "";
            }
        }
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        if (config.enableCustomCollectionLog()
            && (event.getScriptId() == 1903 || event.getScriptId() == 1906)
            && this.examineLogWidgetNode != null
        ) {
            // render UI
            /*
             * TITLE
             */
            Widget skillGuideUIContainer = client.getWidget(SKILL_GUIDE_WIDGET, 3);
            if (skillGuideUIContainer == null) {
                return;
            }
            Widget[] skillGuideUIParts = skillGuideUIContainer.getDynamicChildren();
            int logCount = itemExamineLogs.size() + npcExamineLogs.size() + objectExamineLogs.size();
            int maxLogCount = fullItemExamineLogs.size() + fullNpcExamineLogs.size() + fullObjectExamineLogs.size();
            skillGuideUIParts[1].setText("Examine Log - " + logCount + "/" + maxLogCount);

            /*
             * TABS
             */
            Widget skillGuideTabsContainer = client.getWidget(SKILL_GUIDE_WIDGET, 7);
            if (skillGuideTabsContainer == null) {
                return;
            }
            Widget[] skillGuideTabParts = skillGuideTabsContainer.getDynamicChildren();
            if (skillGuideTabParts[0].getName().isEmpty()) {
                this.selectedTab = "items";
            } else {
                skillGuideTabParts[0].setName("<col=ff9040>Items</col>");
            }
            skillGuideTabParts[8].setText("Items");

            if (skillGuideTabParts[9].getName().isEmpty()) {
                this.selectedTab = "npcs";
            } else {
                skillGuideTabParts[9].setName("<col=ff9040>Npcs</col>");
            }
            skillGuideTabParts[17].setText("Npcs");

            if (skillGuideTabParts[18].getName().isEmpty()) {
                this.selectedTab = "objects";
            } else {
                skillGuideTabParts[18].setName("<col=ff9040>Objects</col>");
            }
            skillGuideTabParts[26].setText("Objects");

            /*
             * Entries
             */
            Widget rowEntriesContainer = client.getWidget(SKILL_GUIDE_WIDGET, 8);
            if (rowEntriesContainer == null) {
                return;
            }
            rowEntriesContainer.deleteAllChildren();
            int y = 0;
            switch (selectedTab) {
                case "items": {
                    y = renderItemExamineLog(rowEntriesContainer);
                    break;
                }
                case "npcs": {
                    y = renderNPCExamineLog(rowEntriesContainer);
                    break;
                }
                case "objects": {
                    y = renderObjectExamineLog(rowEntriesContainer);
                    break;
                }
            }
            /*
             * Scroll Bar
             */
            Widget entriesScrollBar = client.getWidget(SKILL_GUIDE_WIDGET, 10);
            if (entriesScrollBar != null) {
                rowEntriesContainer.setScrollHeight(y);
                int scrollHeight = (rowEntriesContainer.getScrollY() * y) / rowEntriesContainer.getScrollHeight();
                rowEntriesContainer.revalidateScroll();
                clientThread.invokeLater(() ->
                     client.runScript(
                         ScriptID.UPDATE_SCROLLBAR,
                         entriesScrollBar.getId(),
                         rowEntriesContainer.getId(),
                         scrollHeight
                    )
                );
                rowEntriesContainer.setScrollY(0);
                entriesScrollBar.setScrollY(0);
            }
        }
    }

    private int renderItemExamineLog(Widget rowEntriesContainer) {
        int index = 0;
        int y = 0;

        for (String key : itemExamineLogs.keySet()) {
            BaseExamineLog itemExamineLog = itemExamineLogs.get(key);
            String[] unlockedExamineTexts = {itemExamineLog.getExamineText()};
            String name = itemExamineLog.getId() == BANK_NOTE_ITEM_ID
                    ? "Bank note"
                    : key;
            int rowHeight = renderExamineLogRow(
                rowEntriesContainer,
                name,
                unlockedExamineTexts,
                new ArrayList<>(),
                index,
                y,
                itemExamineLog.getId()
            );
            index++;
            y += rowHeight;
        }
        if (config.showAllHiddenLogs()) {
            for (String key : fullItemExamineLogs.keySet()) {
                // filter out logs you have first
                if (itemExamineLogs.get(key) != null) {
                    continue;
                }
                int rowHeight = renderExamineLogRow(
                    rowEntriesContainer,
                    key,
                    new ArrayList<String>().toArray(String[]::new),
                    Collections.singletonList(fullItemExamineLogs.get(key)),
                    index,
                    y,
                    -1
                );
                index++;
                y += rowHeight;
            }
        }
        return y;
    }

    private int renderNPCExamineLog(Widget rowEntriesContainer) {
        int index = 0;
        int y = 0;

        for (String key : npcExamineLogs.keySet()) {
            NPCExamineLog npcExamineLog = npcExamineLogs.get(key);
            Set<String> examinesUnlocked = npcExamineLog.examineLogs.keySet();
            String[] unlockedExamineTexts = npcExamineLog.examineLogs.keySet().toArray(String[]::new);
            List<String> fullLockedExamineTexts = fullNpcExamineLogs.get(key)
                .stream()
                .filter((examine) -> !examinesUnlocked.contains(examine))
                .collect(Collectors.toList());
            int rowHeight = renderExamineLogRow(
                rowEntriesContainer,
                key,
                unlockedExamineTexts,
                fullLockedExamineTexts,
                index,
                y,
                -1
            );
            index++;
            y += rowHeight;
        }
        if (config.showAllHiddenLogs()) {
            for (String key : fullNpcExamineLogs.keySet()) {
                // filter out logs you have first
                if (npcExamineLogs.get(key) != null) {
                    continue;
                }
                int rowHeight = renderExamineLogRow(
                    rowEntriesContainer,
                    key,
                    new ArrayList<String>().toArray(String[]::new),
                    new ArrayList<>(fullNpcExamineLogs.get(key)),
                    index,
                    y,
                    -1
                );
                index++;
                y += rowHeight;
            }
        }
        return y;
    }

    private int renderObjectExamineLog(Widget rowEntriesContainer) {
        int index = 0;
        int y = 0;

        for (String key : objectExamineLogs.keySet()) {
            ObjectExamineLog objectExamineLog = objectExamineLogs.get(key);
            Set<String> examinesUnlocked = objectExamineLog.examineLogs.keySet();
            String[] unlockedExamineTexts = objectExamineLog.examineLogs.keySet().toArray(String[]::new);
            List<String> fullLockedExamineTexts = fullObjectExamineLogs.get(key)
                .stream()
                .filter((examine) -> !examinesUnlocked.contains(examine))
                .collect(Collectors.toList());
            int rowHeight = renderExamineLogRow(
                rowEntriesContainer,
                key,
                unlockedExamineTexts,
                fullLockedExamineTexts,
                index,
                y,
                -1
            );
            index++;
            y += rowHeight;
        }
        if (config.showAllHiddenLogs()) {
            for (String key : fullObjectExamineLogs.keySet()) {
                // filter out logs you have first
                if (objectExamineLogs.get(key) != null) {
                    continue;
                }
                int rowHeight = renderExamineLogRow(
                    rowEntriesContainer,
                    key,
                    new ArrayList<String>().toArray(String[]::new),
                    new ArrayList<>(fullObjectExamineLogs.get(key)),
                    index,
                    y,
                    -1
                );
                index++;
                y += rowHeight;
            }
        }
        return y;
    }

    private int renderExamineLogRow(
        Widget rowEntriesContainer, String key, String[] unlockedExamineTexts,
        List<String> fullLockedExamineTexts, int index, int y, int itemId
    ) {
        final int ODD_OPACITY = 200;
        final int EVEN_OPACITY = 220;
        final int PADDING = 12;
        final int LINE_HEIGHT = 12;
        final int NAME_WIDTH = 130;
        final int ITEM_SIZE = PADDING + LINE_HEIGHT + PADDING;
        // Limit rendering to only 20 as get index out of bounds with > 100 log entries
        final int CHUNK_SIZE = 20;

        int MAX_HINTS = config.hintCount();
        String[] lockedExamineTexts = fullLockedExamineTexts.stream().limit(MAX_HINTS).toArray(String[]::new);
        boolean hasItem = itemId > -1;
        boolean hasCompleted = fullLockedExamineTexts.isEmpty();

        // Background Box widget
        Widget examineLogRowBox = rowEntriesContainer.createChild(-1, WidgetType.RECTANGLE);
        examineLogRowBox.setFilled(true);
        examineLogRowBox.setOpacity(index % 2 == 0 ? ODD_OPACITY : EVEN_OPACITY);
        examineLogRowBox.setBorderType(0);
        examineLogRowBox.setWidthMode(1);
        examineLogRowBox.setOriginalY(y);
        examineLogRowBox.revalidate();

        // Name for the examine log row
        Widget examineLogRowName = rowEntriesContainer.createChild(-1, WidgetType.TEXT);
        examineLogRowName.setTextColor(Integer.parseInt("ff981f", 16));
        examineLogRowName.setLineHeight(LINE_HEIGHT);
        examineLogRowName.setTextShadowed(true);
        examineLogRowName.setFontId(FontID.PLAIN_12);
        examineLogRowName.setOriginalWidth(NAME_WIDTH - PADDING);
        examineLogRowName.setOriginalX(PADDING);
        examineLogRowName.setOriginalY(y + PADDING);
        examineLogRowName.revalidate();

        // Examine text entries for the row
        int examineTextX = NAME_WIDTH + (hasItem ? ITEM_SIZE : 0);
        int textColor = hasCompleted ? Integer.parseInt("dc10d", 16) : Integer.parseInt("ff981f", 16);
        Widget examineLogRowText = rowEntriesContainer.createChild(-1, WidgetType.TEXT);
        examineLogRowText.setTextColor(textColor);
        examineLogRowText.setLineHeight(LINE_HEIGHT);
        examineLogRowText.setTextShadowed(true);
        examineLogRowText.setFontId(FontID.PLAIN_12);
        examineLogRowText.setOriginalWidth(examineTextX);
        examineLogRowText.setWidthMode(1);
        examineLogRowText.setOriginalX(examineTextX);
        examineLogRowText.setOriginalY(y + PADDING);
        examineLogRowText.revalidate();

        // Word wrapping without being able to get the text box height this is works well
        List<String> examineLogText = buildExamineLogTextLines(
            examineLogRowText,
            unlockedExamineTexts,
            lockedExamineTexts,
            fullLockedExamineTexts.size()
        );
        List<String> nameLines = buildExamineLogName(examineLogRowName, key);

        int maxLines = Math.max(examineLogText.size(), nameLines.size());
        int boxHeight = PADDING + maxLines * LINE_HEIGHT + PADDING;

        // Recalculating height once the line count is known
        // Background Box
        examineLogRowBox.setOriginalHeight(boxHeight);
        examineLogRowBox.revalidate();
        // Name
        examineLogRowName.setOriginalHeight(nameLines.size() * LINE_HEIGHT);
        examineLogRowName.setText(String.join("<br>", nameLines));
        examineLogRowName.revalidate();
        // Examine texts
        List<List<String>> examineLogTextChunks = Lists.partition(examineLogText, CHUNK_SIZE);

        examineLogRowText.setOriginalHeight(examineLogTextChunks.get(0).size() * LINE_HEIGHT);
        examineLogRowText.setText(String.join("<br>", examineLogTextChunks.get(0)));
        examineLogRowText.revalidate();

        // Splits text boxes into chunks to avoid reaching capacity for the text font face
        for (int chunk = 1; chunk < examineLogTextChunks.size(); chunk++) {
            List<String> textChunk = examineLogTextChunks.get(chunk);
            examineLogRowText = rowEntriesContainer.createChild(-1, WidgetType.TEXT);
            examineLogRowText.setTextColor(textColor);
            examineLogRowText.setLineHeight(LINE_HEIGHT);
            examineLogRowText.setTextShadowed(true);
            examineLogRowText.setFontId(FontID.PLAIN_12);
            examineLogRowText.setOriginalWidth(examineTextX);
            examineLogRowText.setWidthMode(1);
            examineLogRowText.setOriginalX(examineTextX);
            examineLogRowText.setOriginalY(y + PADDING + chunk * CHUNK_SIZE * LINE_HEIGHT);
            examineLogRowText.setOriginalHeight(textChunk.size() * LINE_HEIGHT);
            examineLogRowText.setText(String.join("<br>", textChunk));
            examineLogRowText.revalidate();
        }

        // Rendering Item sprite for item logs
        // todo - figure out a better way for npcs and objects
        if (hasItem) {
            Widget examineLogRowItem = rowEntriesContainer.createChild(-1, WidgetType.GRAPHIC);
            examineLogRowItem.setItemId(itemId);
            examineLogRowItem.setItemQuantity(-1);
            examineLogRowItem.setOriginalX(NAME_WIDTH);
            examineLogRowItem.setOriginalWidth(ITEM_SIZE);
            examineLogRowItem.setOriginalHeight(ITEM_SIZE - 2);
            examineLogRowItem.setOriginalY((y + 3) + (maxLines - 1) * (LINE_HEIGHT / 2)); // to center the item sprite
            examineLogRowItem.revalidate();
        }

        return boxHeight;
    }

    List<String> buildExamineLogName(Widget examineLogName, String name) {
        FontTypeFace font = examineLogName.getFont();
        int width = examineLogName.getWidth();
        List<String> lines = new ArrayList<>();
        List<String> words = new LinkedList<>(Arrays.asList(name.trim().split("\\s+")));
        StringBuilder line = new StringBuilder().append(words.remove(0));
        for (String word : words) {
            if (font.getTextWidth(line + " " + word) > width) {
                lines.add(line.toString());
                line = new StringBuilder(word);
                continue;
            }
            line.append(" ").append(word);
        }
        lines.add(line.toString());

        return lines;
    }

    List<String> buildExamineLogTextLines(
        Widget examineLogTextBox, String[] unlockedExamineTexts,
        String[] lockedHintExamineTexts, int lockedCount
    ) {
        FontTypeFace font = examineLogTextBox.getFont();
        int width = examineLogTextBox.getWidth();
        int remaining = lockedCount - lockedHintExamineTexts.length;

        List<String> lines = new ArrayList<>();
        for (String unlockedExamineText : unlockedExamineTexts) {
            List<String> words = new LinkedList<>(Arrays.asList(unlockedExamineText.trim().split("\\s+")));
            StringBuilder line = new StringBuilder("- ").append(words.remove(0));
            for (String word : words) {
                if (font.getTextWidth(line + " " + word) > width) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                    continue;
                }
                line.append(" ").append(word);
            }
            lines.add(line.toString());
        }

        for (String lockedHintExamineText : lockedHintExamineTexts) {
            // Hide text
            String examineText = config.enableHiddenHints()
                    ? lockedHintExamineText.replaceAll("[^ ]", ".")
                    : lockedHintExamineText;
            List<String> words = new LinkedList<>(Arrays.asList(examineText.trim().split("\\s+")));
            StringBuilder line = new StringBuilder(ColorUtil.prependColorTag("- ", ColorUtil.fromHex("9f9f9f")));

            line.append(words.remove(0));
            for (String word : words) {
                if (font.getTextWidth(Text.removeTags(line.toString()) + " " + word) > width) {
                    line.append(ColorUtil.CLOSING_COLOR_TAG);
                    lines.add(line.toString());
                    line = new StringBuilder(ColorUtil.prependColorTag(word, ColorUtil.fromHex("9f9f9f")));
                    continue;
                }
                line.append(" ").append(word);
            }
            line.append(ColorUtil.CLOSING_COLOR_TAG);
            lines.add(line.toString());
        }
        if (remaining > 0) {
            String moreExaminesText = String.format("... and %d more still to find", remaining);
            lines.add(ColorUtil.wrapWithColorTag(moreExaminesText, ColorUtil.fromHex("9f9f9f")));
        }

        return lines;
    }
}
