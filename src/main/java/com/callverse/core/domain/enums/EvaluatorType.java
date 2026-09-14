package com.callverse.core.domain.enums;

/**
 * Whether a quality evaluation was produced by the Quality Analyst agent or by a human
 * annotator. Keeping both in one table is what lets inter-rater agreement (Cohen's kappa) be
 * computed with a single self-join rather than a cross-table reconciliation.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum EvaluatorType {
    AI,
    HUMAN
}
