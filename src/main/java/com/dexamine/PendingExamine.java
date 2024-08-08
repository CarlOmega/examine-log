package com.dexamine;

import lombok.Data;
import net.runelite.api.ChatMessageType;

@Data
class PendingExamine
{
    private ChatMessageType type;
    private int id;
    private String name;
    private String examineText;
}
