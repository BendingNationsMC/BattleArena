package org.battleplugins.arena.competition.map;

import org.battleplugins.arena.proxy.Elements;

import java.util.*;

/**
 * Represents a pairing of elements for a map. Matchups can either be legacy
 * (one element per side, unlimited roster), composition-based (explicit
 * element slots per side), or constraint-based (team size with per-element limits).
 */
public final class ElementMatchup {
    private final List<Elements> leftElements;
    private final List<Elements> rightElements;
    private final boolean composition;
    private final boolean constraint;
    private final int constraintTeamSize;
    private final Map<Elements, Integer> constraintMaxPerElement;

    private ElementMatchup(List<Elements> leftElements, List<Elements> rightElements, boolean composition) {
        Objects.requireNonNull(leftElements, "left elements");
        Objects.requireNonNull(rightElements, "right elements");
        if (leftElements.isEmpty()) {
            throw new IllegalArgumentException("Left elements cannot be empty");
        }
        if (rightElements.isEmpty()) {
            throw new IllegalArgumentException("Right elements cannot be empty");
        }

        this.leftElements = List.copyOf(leftElements);
        this.rightElements = List.copyOf(rightElements);
        this.composition = composition;
        this.constraint = false;
        this.constraintTeamSize = 0;
        this.constraintMaxPerElement = Map.of();
    }

    private ElementMatchup(int teamSize, Map<Elements, Integer> limits) {
        if (teamSize <= 0) {
            throw new IllegalArgumentException("teamSize must be positive");
        }
        this.leftElements = List.of();
        this.rightElements = List.of();
        this.composition = false;
        this.constraint = true;
        this.constraintTeamSize = teamSize;

        EnumMap<Elements, Integer> normalized = new EnumMap<>(Elements.class);
        if (limits != null) {
            for (Map.Entry<Elements, Integer> entry : limits.entrySet()) {
                Elements element = Objects.requireNonNull(entry.getKey(), "element");
                Integer limit = entry.getValue();
                if (limit == null) {
                    continue;
                }
                int value = limit;
                if (value <= 0) {
                    continue;
                }
                normalized.put(element, value);
            }
        }
        this.constraintMaxPerElement = Collections.unmodifiableMap(normalized);
    }

    /**
     * Creates a legacy matchup pairing a single element per side. This mirrors
     * the original behavior where each team is composed entirely of that element.
     */
    public static ElementMatchup single(Elements left, Elements right) {
        Objects.requireNonNull(left, "left element");
        Objects.requireNonNull(right, "right element");
        return new ElementMatchup(List.of(left), List.of(right), false);
    }

    /**
     * Creates a composition matchup where each side defines explicit element slots.
     */
    public static ElementMatchup composition(List<Elements> left, List<Elements> right) {
        return new ElementMatchup(left, right, true);
    }

    /**
     * Creates a constraint matchup where both teams have the same per-element limits.
     */
    public static ElementMatchup constraint(int teamSize, Map<Elements, Integer> limits) {
        return new ElementMatchup(teamSize, limits);
    }

    /**
     * Parses a matchup in the form {@code ELEMENTvELEMENT} or {@code ELEMENTvsELEMENT}.
     *
     * @param input the textual representation
     * @return the parsed matchup
     */
    public static ElementMatchup parse(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Matchup cannot be null");
        }

        String cleaned = input.trim().toUpperCase(Locale.ROOT);
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Matchup cannot be empty");
        }

        String delimiter;
        int idx;
        if ((idx = cleaned.indexOf("VS")) >= 0) {
            delimiter = "VS";
        } else if ((idx = cleaned.indexOf('V')) >= 0) {
            delimiter = "V";
        } else {
            throw new IllegalArgumentException("Matchup must contain a 'v' separating two elements");
        }

        String leftPart = cleaned.substring(0, idx).trim();
        String rightPart = cleaned.substring(idx + delimiter.length()).trim();

        if (leftPart.isEmpty() || rightPart.isEmpty()) {
            throw new IllegalArgumentException("Matchup must contain two elements");
        }

        Elements left = Elements.valueOf(leftPart);
        Elements right = Elements.valueOf(rightPart);
        return ElementMatchup.single(left, right);
    }

    /**
     * Parses a map-based matchup definition in the form:
     * <pre>
     * left:
     *   - FIRE
     *   - EARTH
     * right:
     *   - AIR
     *   - WATER
     * </pre>
     *
     * @param raw the raw map from configuration
     * @return the parsed matchup
     */
    public static ElementMatchup fromMap(Map<?, ?> raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Matchup map cannot be null");
        }

        if (raw.containsKey("left") || raw.containsKey("right")) {
            Object left = raw.get("left");
            Object right = raw.get("right");
            List<Elements> leftElements = parseElementList(left, "left");
            List<Elements> rightElements = parseElementList(right, "right");
            return ElementMatchup.composition(leftElements, rightElements);
        }

        return parseConstraintMap(raw);
    }

    private static List<Elements> parseElementList(Object raw, String side) {
        Iterable<?> iterable;
        if (raw instanceof Iterable<?> it) {
            iterable = it;
        } else {
            iterable = Collections.singletonList(raw);
        }

        List<Elements> result = new ArrayList<>();
        for (Object entry : iterable) {
            if (entry == null) {
                continue;
            }

            Elements element;
            if (entry instanceof Elements e) {
                element = e;
            } else {
                element = Elements.valueOf(entry.toString().trim().toUpperCase(Locale.ROOT));
            }
            result.add(element);
        }

        if (result.isEmpty()) {
            throw new IllegalArgumentException("Matchup " + side + " side must contain at least one element");
        }

        return result;
    }

    public List<Elements> leftElements() {
        return Collections.unmodifiableList(this.leftElements);
    }

    public List<Elements> rightElements() {
        return Collections.unmodifiableList(this.rightElements);
    }

    public boolean isComposition() {
        return this.composition;
    }

    public boolean isConstraint() {
        return this.constraint;
    }

    public int constraintTeamSize() {
        return this.constraintTeamSize;
    }

    public Map<Elements, Integer> constraintMaxPerElement() {
        return this.constraintMaxPerElement;
    }

    /**
     * Serializes this matchup back to configuration form.
     *
     * @return config-safe object (String for legacy, Map for compositions)
     */
    public Object toConfigValue() {
        if (this.constraint) {
            Map<String, Object> serialized = new LinkedHashMap<>();
            serialized.put("team-size", this.constraintTeamSize);
            if (!this.constraintMaxPerElement.isEmpty()) {
                Map<String, Integer> limits = new LinkedHashMap<>();
                this.constraintMaxPerElement.forEach((element, limit) -> limits.put(element.name(), limit));
                serialized.put("max-per-element", limits);
            }
            return serialized;
        }

        if (!this.composition && this.leftElements.size() == 1 && this.rightElements.size() == 1) {
            return this.leftElements.get(0).name() + "v" + this.rightElements.get(0).name();
        }

        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("left", serializeSide(this.leftElements));
        map.put("right", serializeSide(this.rightElements));
        return map;
    }

    private static List<String> serializeSide(List<Elements> elements) {
        List<String> serialized = new ArrayList<>(elements.size());
        for (Elements element : elements) {
            serialized.add(element.name());
        }
        return serialized;
    }

    private static ElementMatchup parseConstraintMap(Map<?, ?> raw) {
        Map<?, ?> constraintSection;
        Object nested = raw.get("constraint");
        if (nested instanceof Map<?, ?> nestedMap) {
            constraintSection = nestedMap;
        } else {
            constraintSection = raw;
        }

        Object teamSizeValue = firstNonNull(constraintSection.get("team-size"),
                constraintSection.get("team_size"),
                constraintSection.get("size"));
        if (teamSizeValue == null) {
            throw new IllegalArgumentException("Constraint matchup must define 'team-size'");
        }

        int teamSize = parseInteger(teamSizeValue);
        if (teamSize <= 0) {
            throw new IllegalArgumentException("Constraint matchup team-size must be positive");
        }

        Object limitsObject = constraintSection.get("max-per-element");
        if (limitsObject == null) {
            limitsObject = constraintSection.get("max_per_element");
        }

        Map<Elements, Integer> limits = new EnumMap<>(Elements.class);
        if (limitsObject instanceof Map<?, ?> limitsMap) {
            for (Map.Entry<?, ?> entry : limitsMap.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                Elements element = Elements.valueOf(entry.getKey().toString().trim().toUpperCase(Locale.ROOT));
                int limit = parseInteger(entry.getValue());
                if (limit > 0) {
                    limits.put(element, limit);
                }
            }
        }

        return ElementMatchup.constraint(teamSize, limits);
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static int parseInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }

        if (value instanceof String s) {
            return Integer.parseInt(s.trim());
        }

        throw new IllegalArgumentException("Value '" + value + "' is not a valid integer");
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ElementMatchup other)) {
            return false;
        }
        return this.composition == other.composition
                && this.constraint == other.constraint
                && this.leftElements.equals(other.leftElements)
                && this.rightElements.equals(other.rightElements)
                && this.constraintTeamSize == other.constraintTeamSize
                && this.constraintMaxPerElement.equals(other.constraintMaxPerElement);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.leftElements, this.rightElements, this.composition, this.constraint, this.constraintTeamSize, this.constraintMaxPerElement);
    }

    @Override
    public String toString() {
        if (this.constraint) {
            return "ElementMatchup{teamSize=" + this.constraintTeamSize + ", limits=" + this.constraintMaxPerElement + '}';
        }
        if (!this.composition && this.leftElements.size() == 1 && this.rightElements.size() == 1) {
            return this.leftElements.get(0).name() + "v" + this.rightElements.get(0).name();
        }
        return "ElementMatchup{left=" + this.leftElements + ", right=" + this.rightElements + '}';
    }
}
