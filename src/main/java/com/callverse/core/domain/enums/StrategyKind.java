package com.callverse.core.domain.enums;

/**
 * Family a control strategy belongs to. BASELINE strategies exist to be beaten: without a
 * measured baseline the reinforcement-learning result has nothing to be compared against and
 * the experiment claims nothing.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum StrategyKind {
    BASELINE,
    HEURISTIC,
    RL
}
