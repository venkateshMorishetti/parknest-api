package com.parknest.api.listings;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One row of listing_photos (its position comes from the list order). */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ListingPhoto {

    private String fileId;
    private String caption;
}
