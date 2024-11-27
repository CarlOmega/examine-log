package com.dexamine;

import lombok.Value;
import net.runelite.api.ChatMessageType;
import net.runelite.api.coords.WorldPoint;

@Value
public class ExamineLogEntry {
    int id;
    ChatMessageType type;
    String name;
    String examineText;
    String timestamp;
    WorldPoint worldPoint;
}

