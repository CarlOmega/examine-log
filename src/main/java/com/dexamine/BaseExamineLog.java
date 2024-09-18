package com.dexamine;

import lombok.Value;
import net.runelite.api.coords.WorldPoint;

@Value
public class BaseExamineLog {
    int id;
    String examineText;
    String timestamp;
    WorldPoint worldPoint;
}
