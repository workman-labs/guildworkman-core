package com.guildworkman.api.discovery;

/**
 * One ranked row from the worker discovery query. A Spring Data interface
 * projection over the native SQL — the query aliases every column to match a
 * getter here (quoted, so Postgres keeps the camelCase).
 */
public interface WorkerSearchRow {

    Long getId();

    String getFullName();

    String getUsername();

    /** {@code Category} name; the column is {@code EnumType.STRING}. */
    String getCategory();

    double getLatitude();

    double getLongitude();

    Boolean getAvailable();

    /** Exact great-circle distance from the caller, kilometres. */
    double getDistanceKm();

    /** Materialised reputation, {@code [0,1]}; the query has already COALESCEd a missing snapshot to the fallback. */
    double getReputationScore();

    long getReviewCount();

    /** The blended ranking score this ordering used, {@code [0,1]}. */
    double getScore();
}
