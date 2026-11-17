-- The app allows spot names of 3 to 60 characters ("Villa Driveway" can be short). V1 asked for 5 to 80.
ALTER TABLE listings DROP CONSTRAINT listings_title_check;
ALTER TABLE listings ADD CONSTRAINT listings_title_check CHECK (length(btrim(title)) BETWEEN 3 AND 80);
