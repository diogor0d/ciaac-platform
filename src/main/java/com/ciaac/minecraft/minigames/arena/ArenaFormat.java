package com.ciaac.minecraft.minigames.arena;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A validated team-size pairing for an arena match. */
public record ArenaFormat(int teamASize, int teamBSize) {
    private static final Pattern NOTATION = Pattern.compile("(\\d+)\\s*[vV]\\s*(\\d+)");
    private static final int MAX_TEAM_SIZE = 3;

    public ArenaFormat {
        if (teamASize < 1 || teamBSize < 1) {
            throw new IllegalArgumentException("Team sizes must be positive");
        }
        if (teamASize > MAX_TEAM_SIZE || teamBSize > MAX_TEAM_SIZE) {
            throw new IllegalArgumentException("Team sizes cannot exceed the arena maximum of " + MAX_TEAM_SIZE);
        }
    }

    public static ArenaFormat standard(int playersPerTeam) {
        if (playersPerTeam < 1 || playersPerTeam > 3) {
            throw new IllegalArgumentException("Standard formats are 1v1, 2v2, and 3v3");
        }
        return new ArenaFormat(playersPerTeam, playersPerTeam);
    }

    public static ArenaFormat asymmetric(int teamASize, int teamBSize, boolean enabled) {
        if (teamASize == teamBSize) {
            return standard(teamASize);
        }
        if (!enabled) {
            throw new IllegalArgumentException("Asymmetric formats are disabled");
        }
        return new ArenaFormat(teamASize, teamBSize);
    }

    /** Parses standard formats and, when enabled, configured asymmetric formats. */
    public static ArenaFormat parse(String notation, boolean asymmetricEnabled) {
        Objects.requireNonNull(notation, "notation");
        Matcher matcher = NOTATION.matcher(notation.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Format must use NxN notation, for example 2v2");
        }

        int teamA = parseSize(matcher.group(1));
        int teamB = parseSize(matcher.group(2));
        return asymmetric(teamA, teamB, asymmetricEnabled);
    }

    public static ArenaFormat parse(String notation, ArenaFormatPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        Matcher matcher = NOTATION.matcher(Objects.requireNonNull(notation, "notation").trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Format must use NxN notation, for example 2v2");
        }
        ArenaFormat format = new ArenaFormat(parseSize(matcher.group(1)), parseSize(matcher.group(2)));
        policy.requireSupported(format);
        return format;
    }

    private static int parseSize(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid team size", exception);
        }
    }

    public boolean isAsymmetric() {
        return teamASize != teamBSize;
    }

    public String notation() {
        return teamASize + "v" + teamBSize;
    }

    @Override
    public String toString() {
        return notation().toLowerCase(Locale.ROOT);
    }
}
