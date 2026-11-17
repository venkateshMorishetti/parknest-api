package com.parknest.api.listings;

import java.util.List;

/** Search needs a query built from the filters, so it is written by hand in ListingSearchImpl. */
public interface ListingSearch {

    /** Ids of bookable spots inside the box that match the filters and have a free bay for the whole stay. */
    List<String> searchIds(SearchCriteria criteria);
}
