package com.ciaac.minecraft.minigames.anvildodge;

import java.util.List;

public record AnvilWave(int number, List<Integer> hazardCells) {
    public AnvilWave { if (number < 1 || hazardCells == null || hazardCells.isEmpty()) throw new IllegalArgumentException("invalid wave"); hazardCells = List.copyOf(hazardCells); }
}
