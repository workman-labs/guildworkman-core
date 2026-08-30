package com.guildworkman.api.discovery;

/** One {@code (value, count)} pair of a facet. Interface projection over the grouped native count queries. */
public interface FacetRow {

    String getValue();

    long getCount();
}
