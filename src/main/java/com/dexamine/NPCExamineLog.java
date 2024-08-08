package com.dexamine;

import lombok.Data;

import java.util.HashSet;
import java.util.Set;

@Data
public class NPCExamineLog {
    Set<Integer> ids = new HashSet<>();
    Set<String> examineTexts = new HashSet<>();
}
