package com.guildworkman.api.discovery.pagination;

/**
 * The keyset position of the last row on a page: the {@code (rankScore, workerId)}
 * the next page continues strictly after.
 *
 * <p>{@code workerId} is the deterministic tie-break that makes the sort total,
 * so two workers with an identical {@code rankScore} are still paged through in
 * a fixed order and neither is skipped nor repeated.
 */
public record SearchCursor(double rankScore, long workerId) {
}
