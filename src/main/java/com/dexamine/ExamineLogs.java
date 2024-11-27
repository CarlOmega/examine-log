package com.dexamine;

import lombok.Value;
import net.runelite.api.ChatMessageType;

import java.util.*;
import java.util.stream.Collectors;

@Value
public class ExamineLogs {
    Map<Integer, ExamineLogEntry> itemExamineLogs = new HashMap<>();
    Map<Integer, ExamineLogEntry> npcExamineLogs = new HashMap<>();
    Map<Integer, ExamineLogEntry> objectExamineLogs = new HashMap<>();

    public Map<String, List<ExamineLogEntry>> getLogs(ChatMessageType type) {
        switch (type) {
            case ITEM_EXAMINE:
                return itemExamineLogs.values().stream().collect(
                        Collectors.groupingBy(ExamineLogEntry::getName,
                                              HashMap::new,
                                              Collectors.toCollection(ArrayList::new)
                        )
                );
            case NPC_EXAMINE:
                return npcExamineLogs.values().stream().collect(
                        Collectors.groupingBy(ExamineLogEntry::getName,
                                              HashMap::new,
                                              Collectors.toCollection(ArrayList::new)
                        )
                );
            case OBJECT_EXAMINE:
                return objectExamineLogs.values().stream().collect(
                        Collectors.groupingBy(ExamineLogEntry::getName,
                                              HashMap::new,
                                              Collectors.toCollection(ArrayList::new)
                        )
                );
        }
        return new HashMap<>();
    }

    public void add(ExamineLogEntry examineLogEntry) {
        switch (examineLogEntry.getType()) {
            case ITEM_EXAMINE:
                itemExamineLogs.putIfAbsent(examineLogEntry.getId(), examineLogEntry);
                break;
            case NPC_EXAMINE:
                npcExamineLogs.putIfAbsent(examineLogEntry.getId(), examineLogEntry);
                break;
            case OBJECT_EXAMINE:
                objectExamineLogs.putIfAbsent(examineLogEntry.getId(), examineLogEntry);
                break;
        }
    }

    public boolean isNew(ExamineLogEntry entry) {
        return this.isNew(entry.getType(), entry.getName());
    }

    public boolean isNew(ChatMessageType type, String name) {
        switch (type) {
            case ITEM_EXAMINE: {
                return itemExamineLogs.values()
                        .stream()
                        .parallel()
                        .noneMatch(examine -> Objects.equals(examine.getName(), name
                        ));
            }
            case NPC_EXAMINE:
                return npcExamineLogs.values()
                        .stream()
                        .parallel()
                        .noneMatch(examine -> Objects.equals(examine.getName(), name
                        ));
            case OBJECT_EXAMINE:
                return objectExamineLogs.values()
                        .stream()
                        .parallel()
                        .noneMatch(examine -> Objects.equals(examine.getName(), name
                        ));
        }
        return false;
    }

    public int count() {
        return itemExamineLogs.size() + npcExamineLogs.size() + objectExamineLogs.size();
    }

    public boolean isEmpty() {
        return itemExamineLogs.isEmpty() && npcExamineLogs.isEmpty() && objectExamineLogs.isEmpty();
    }
}
