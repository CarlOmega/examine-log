package com.dexamine;

import lombok.Data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

@Data
public class NPCExamineLog {
    Set<Integer> ids = new HashSet<>();
    HashMap<String, BaseExamineLog> examineLogs = new HashMap<>();
}
