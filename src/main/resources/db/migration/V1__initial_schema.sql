-- ParkNest database schema, version 1 (PostgreSQL 15+).
-- One database per environment (testing, production). No extensions needed.
--
-- Conventions
--   * IDs are text with a type prefix (usr_, lst_, bk_...), created by the API (prefix + ULID). They match the API.
--   * Money is integer paise (₹150 = 15000) in bigint columns ending in _paise. Currency is always INR.
--   * Times are timestamptz (stored in UTC, shown in Asia/Kolkata by the app).
--   * Enums are text + CHECK, so adding a value is a one-line migration.
--   * Every table has created_at; tables that change have updated_at (kept by a trigger).


CREATE FUNCTION set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END;
$$;

-- =====================================================================================================================
-- People and sign-in
-- =====================================================================================================================

-- Everyone: drivers, hosts and the admin. A user can do all three.
CREATE TABLE users (
  id                 text PRIMARY KEY CHECK (id LIKE 'usr\_%'),
  name               text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 80),
  email              text CHECK (email ~ '^[^\s@]+@[^\s@]+\.[^\s@]{2,}$'),
  email_verified_at  timestamptz,
  -- Saved only once verified by OTP. NULL for a Google sign-in that has not added a number yet.
  phone              text UNIQUE CHECK (phone ~ '^\+91[6-9][0-9]{9}$'),
  phone_verified_at  timestamptz,
  avatar_url         text,
  roles              text[] NOT NULL DEFAULT '{}' CHECK (roles <@ ARRAY['ADMIN']),
  status             text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'BLOCKED')),
  blocked_reason     text,
  blocked_at         timestamptz,
  last_active_at     timestamptz,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  CHECK ((phone IS NULL) = (phone_verified_at IS NULL)),
  CHECK (status = 'ACTIVE' OR (blocked_reason IS NOT NULL AND blocked_at IS NOT NULL))
);
CREATE UNIQUE INDEX users_email_key ON users (lower(email)) WHERE email IS NOT NULL;
CREATE TRIGGER users_updated_at BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- How a user signs in: their phone (OTP) and/or Google. One row per method.
CREATE TABLE auth_identities (
  id                text PRIMARY KEY CHECK (id LIKE 'aid\_%'),
  user_id           text NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  provider          text NOT NULL CHECK (provider IN ('PHONE', 'GOOGLE')),
  -- PHONE: the +91 number. GOOGLE: the "sub" from the Google ID token (never the email, which can change).
  provider_subject  text NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  last_used_at      timestamptz,
  UNIQUE (provider, provider_subject),
  UNIQUE (user_id, provider)
);

-- OTP codes for logging in and for adding a number after Google sign-in. Only a hash of the code is stored.
CREATE TABLE otp_requests (
  id           text PRIMARY KEY CHECK (id LIKE 'otp\_%'),
  phone        text NOT NULL CHECK (phone ~ '^\+91[6-9][0-9]{9}$'),
  purpose      text NOT NULL CHECK (purpose IN ('LOGIN', 'LINK_PHONE')),
  user_id      text REFERENCES users (id) ON DELETE CASCADE,  -- LINK_PHONE only: who is adding the number
  code_hash    text NOT NULL,
  attempts     smallint NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
  expires_at   timestamptz NOT NULL,
  consumed_at  timestamptz,
  ip_address   inet,
  created_at   timestamptz NOT NULL DEFAULT now(),
  CHECK ((purpose = 'LINK_PHONE') = (user_id IS NOT NULL))
);
-- Rate limits ("3 codes per number per 10 minutes") and cleanup of old rows.
CREATE INDEX otp_requests_phone_recent ON otp_requests (phone, created_at DESC);

-- Refresh tokens (the access token is a short-lived JWT and is not stored). Only a hash is stored.
CREATE TABLE sessions (
  id                  text PRIMARY KEY CHECK (id LIKE 'ses\_%'),
  user_id             text NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  refresh_token_hash  text NOT NULL UNIQUE,
  user_agent          text,
  ip_address          inet,
  created_at          timestamptz NOT NULL DEFAULT now(),
  expires_at          timestamptz NOT NULL,
  revoked_at          timestamptz  -- set on logout, and for all sessions when the user is blocked
);
CREATE INDEX sessions_user ON sessions (user_id) WHERE revoked_at IS NULL;

-- A driver's saved vehicles (up to 5, checked by the API).
CREATE TABLE vehicles (
  id                   text PRIMARY KEY CHECK (id LIKE 'veh\_%'),
  user_id              text NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  -- Stored without spaces, upper case: "KA01AB1234" (shown as "KA 01 AB 1234").
  registration_number  text NOT NULL CHECK (registration_number ~ '^[A-Z0-9]{6,11}$'),
  type                 text NOT NULL CHECK (type IN ('HATCHBACK', 'SEDAN', 'SUV', 'BIKE')),
  nickname             text CHECK (length(nickname) <= 30),
  is_default           boolean NOT NULL DEFAULT false,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  deleted_at           timestamptz  -- soft delete: old bookings still point here
);
CREATE UNIQUE INDEX vehicles_user_registration ON vehicles (user_id, registration_number) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX vehicles_one_default ON vehicles (user_id) WHERE is_default AND deleted_at IS NULL;
CREATE TRIGGER vehicles_updated_at BEFORE UPDATE ON vehicles FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Driver and host notification choices: { "newRequests": { "sms": true, "email": true, "push": false }, ... }
CREATE TABLE notification_preferences (
  user_id     text PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
  settings    jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(settings) = 'object'),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER notification_preferences_updated_at BEFORE UPDATE ON notification_preferences FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- =====================================================================================================================
-- Hosting
-- =====================================================================================================================

-- Created when a user starts hosting (/host/start). No documents and no review of the person:
-- a verified phone, a payout account and the accepted terms are enough. Spots are reviewed instead.
CREATE TABLE host_profiles (
  user_id            text PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
  status             text NOT NULL DEFAULT 'NOT_STARTED' CHECK (status IN ('NOT_STARTED', 'ACTIVE')),
  terms_version      text,
  terms_accepted_at  timestamptz,
  activated_at       timestamptz,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  CHECK (status = 'NOT_STARTED' OR (terms_version IS NOT NULL AND terms_accepted_at IS NOT NULL AND activated_at IS NOT NULL))
);
CREATE TRIGGER host_profiles_updated_at BEFORE UPDATE ON host_profiles FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Where a host's earnings go. Changing it adds a new row and marks the old one replaced, so past payouts keep
-- pointing at the account they were actually sent to.
CREATE TABLE payout_accounts (
  id                        text PRIMARY KEY CHECK (id LIKE 'pa\_%'),
  user_id                   text NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  method                    text NOT NULL CHECK (method IN ('UPI', 'BANK_ACCOUNT')),
  upi_id                    text CHECK (upi_id ~ '^[A-Za-z0-9._-]{2,200}@[A-Za-z][A-Za-z0-9.]{1,64}$'),
  account_holder_name       text,
  -- Full account number encrypted by the API (key outside the database); only the last 4 digits in clear.
  account_number_encrypted  bytea,
  account_number_last4      char(4) CHECK (account_number_last4 ~ '^[0-9]{4}$'),
  ifsc                      text CHECK (ifsc ~ '^[A-Z]{4}0[A-Z0-9]{6}$'),
  -- Masked text shown in the app: "ve••••sh@okaxis" or "HDFC ••••4410".
  display                   text NOT NULL,
  -- ₹1 test transfer ("penny drop") result and the name the bank returned.
  status                    text NOT NULL DEFAULT 'VERIFYING' CHECK (status IN ('VERIFYING', 'VERIFIED', 'FAILED')),
  name_at_bank              text,
  verified_at               timestamptz,
  replaced_at               timestamptz,  -- NULL = the account in use
  created_at                timestamptz NOT NULL DEFAULT now(),
  CHECK (
    (method = 'UPI' AND upi_id IS NOT NULL AND account_number_encrypted IS NULL AND ifsc IS NULL)
    OR (method = 'BANK_ACCOUNT' AND upi_id IS NULL AND account_holder_name IS NOT NULL
        AND account_number_encrypted IS NOT NULL AND account_number_last4 IS NOT NULL AND ifsc IS NOT NULL)
  )
);
CREATE UNIQUE INDEX payout_accounts_current ON payout_accounts (user_id) WHERE replaced_at IS NULL;

-- Uploaded files (spot photos for now). The file lives in object storage (S3 or similar) under storage_key.
CREATE TABLE files (
  id             text PRIMARY KEY CHECK (id LIKE 'file\_%'),
  owner_id       text NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  purpose        text NOT NULL CHECK (purpose IN ('LISTING_PHOTO')),
  storage_key    text NOT NULL UNIQUE,
  mime_type      text NOT NULL CHECK (mime_type IN ('image/jpeg', 'image/png', 'image/webp')),
  size_bytes     integer NOT NULL CHECK (size_bytes BETWEEN 1 AND 5242880),
  original_name  text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  deleted_at     timestamptz
);
CREATE INDEX files_owner ON files (owner_id);

-- A parking spot. bays = how many identical cars it fits (1 = a single spot).
CREATE TABLE listings (
  id                  text PRIMARY KEY CHECK (id LIKE 'lst\_%'),
  host_id             text NOT NULL REFERENCES users (id),
  title               text NOT NULL CHECK (length(btrim(title)) BETWEEN 5 AND 80),
  description         text CHECK (length(description) <= 1000),
  spot_type           text NOT NULL CHECK (spot_type IN ('COVERED', 'OPEN', 'BASEMENT')),
  bays                smallint NOT NULL DEFAULT 1 CHECK (bays BETWEEN 1 AND 50),
  bay_label           text CHECK (length(bay_label) <= 60),
  vehicle_types       text[] NOT NULL CHECK (cardinality(vehicle_types) > 0 AND vehicle_types <@ ARRAY['HATCHBACK', 'SEDAN', 'SUV', 'BIKE']),
  security_features   text[] NOT NULL DEFAULT '{}' CHECK (security_features <@ ARRAY['CCTV', 'GATED', 'SECURITY_GUARD', 'WELL_LIT']),
  entry_instructions  text CHECK (length(entry_instructions) <= 500),  -- shown to drivers only after payment
  -- Address and map pin (on the entrance).
  address_line1       text NOT NULL,
  area                text NOT NULL,
  city                text NOT NULL,
  state               text NOT NULL,
  pincode             char(6) NOT NULL CHECK (pincode ~ '^[1-9][0-9]{5}$'),
  lat                 numeric(9, 6) NOT NULL CHECK (lat BETWEEN 6 AND 37.5),
  lng                 numeric(9, 6) NOT NULL CHECK (lng BETWEEN 68 AND 97.5),
  -- Price per bay.
  price_per_hour_paise  bigint NOT NULL CHECK (price_per_hour_paise > 0),
  price_per_day_paise   bigint NOT NULL CHECK (price_per_day_paise > 0),
  -- Access hours: open all day, or a daily window.
  is_24x7             boolean NOT NULL DEFAULT true,
  opens_at            time,
  closes_at           time,
  booking_mode        text NOT NULL CHECK (booking_mode IN ('INSTANT', 'HOST_APPROVAL')),
  cancellation_hours  smallint NOT NULL DEFAULT 24 CHECK (cancellation_hours BETWEEN 0 AND 168),
  -- DRAFT -> NEEDS_APPROVAL -> ACTIVE (bookable) or REJECTED (review_note says why). ACTIVE <-> PAUSED by the host.
  -- A live spot whose address or pin moves goes back to NEEDS_APPROVAL.
  status              text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'NEEDS_APPROVAL', 'ACTIVE', 'PAUSED', 'REJECTED')),
  review_reason       text CHECK (review_reason IN ('NEW', 'LOCATION_CHANGED', 'RESUBMITTED')),
  review_note         text,
  submitted_at        timestamptz,
  reviewed_at         timestamptz,
  reviewed_by         text REFERENCES users (id),
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  deleted_at          timestamptz,  -- soft delete: bookings keep their spot
  CHECK (is_24x7 OR (opens_at IS NOT NULL AND closes_at IS NOT NULL AND opens_at <> closes_at)),
  CHECK (status <> 'REJECTED' OR review_note IS NOT NULL),
  CHECK (status <> 'NEEDS_APPROVAL' OR (submitted_at IS NOT NULL AND review_reason IS NOT NULL))
);
CREATE INDEX listings_host ON listings (host_id) WHERE deleted_at IS NULL;
-- Search: bookable spots inside the map's bounds ("Search this area") or near a point.
CREATE INDEX listings_search ON listings (lat, lng) WHERE status = 'ACTIVE' AND deleted_at IS NULL;
-- Admin queue, oldest first.
CREATE INDEX listings_review_queue ON listings (submitted_at) WHERE status = 'NEEDS_APPROVAL';
CREATE TRIGGER listings_updated_at BEFORE UPDATE ON listings FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Up to 8 photos per spot; position 0 is the cover.
CREATE TABLE listing_photos (
  listing_id  text NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
  position    smallint NOT NULL CHECK (position BETWEEN 0 AND 7),
  file_id     text NOT NULL UNIQUE REFERENCES files (id),
  caption     text CHECK (length(caption) <= 80),
  PRIMARY KEY (listing_id, position)
);

-- =====================================================================================================================
-- Bookings and money
-- =====================================================================================================================

-- One row per booking or booking request.
--   INSTANT:       PENDING_PAYMENT (spot held until hold_expires_at) -> CONFIRMED, or EXPIRED.
--   HOST_APPROVAL: PENDING_HOST_APPROVAL -> PENDING_PAYMENT (approved) -> CONFIRMED, or DECLINED.
--                  Several drivers may request the same times; when the host approves one and no bay is left,
--                  the other overlapping requests become DECLINED with declined_by_booking_id set.
--   Then CONFIRMED -> CHECKED_IN -> COMPLETED, or CANCELLED (by driver, host or ParkNest).
CREATE TABLE bookings (
  id                       text PRIMARY KEY CHECK (id LIKE 'bk\_%'),
  booking_ref              text NOT NULL UNIQUE CHECK (booking_ref ~ '^PN-[0-9]{5,}$'),
  listing_id               text NOT NULL REFERENCES listings (id),
  driver_id                text NOT NULL REFERENCES users (id),
  host_id                  text NOT NULL REFERENCES users (id),  -- copied from the listing, for host screens
  booking_mode             text NOT NULL CHECK (booking_mode IN ('INSTANT', 'HOST_APPROVAL')),
  status                   text NOT NULL CHECK (status IN ('PENDING_HOST_APPROVAL', 'PENDING_PAYMENT', 'CONFIRMED', 'CHECKED_IN',
                                                           'COMPLETED', 'EXPIRED', 'DECLINED', 'CANCELLED')),
  start_at                 timestamptz NOT NULL,
  end_at                   timestamptz NOT NULL,
  -- Vehicle at the time of booking (the saved vehicle may change or be removed later). Required before payment.
  vehicle_id               text REFERENCES vehicles (id),
  vehicle_type             text NOT NULL CHECK (vehicle_type IN ('HATCHBACK', 'SEDAN', 'SUV', 'BIKE')),
  vehicle_registration     text CHECK (vehicle_registration ~ '^[A-Z0-9]{6,11}$'),
  -- Price, fixed when the booking is created: parking + service fee + GST on the fee = total.
  parking_paise            bigint NOT NULL CHECK (parking_paise > 0),
  service_fee_paise        bigint NOT NULL CHECK (service_fee_paise >= 0),
  gst_paise                bigint NOT NULL CHECK (gst_paise >= 0),
  total_paise              bigint NOT NULL,
  host_earnings_paise      bigint NOT NULL CHECK (host_earnings_paise >= 0),
  price_lines              jsonb NOT NULL DEFAULT '[]',  -- the quote's line items, as shown to the driver
  hold_expires_at          timestamptz,  -- PENDING_PAYMENT only
  -- Host decision (HOST_APPROVAL).
  host_decided_at          timestamptz,
  decline_reason           text,
  declined_by_booking_id   text REFERENCES bookings (id),  -- set when declined automatically (no bay left)
  -- Lifecycle.
  confirmed_at             timestamptz,
  checked_in_at            timestamptz,
  checked_out_at           timestamptz,
  cancelled_at             timestamptz,
  cancelled_by             text CHECK (cancelled_by IN ('DRIVER', 'HOST', 'ADMIN', 'SYSTEM')),
  cancellation_reason      text,
  created_at               timestamptz NOT NULL DEFAULT now(),
  updated_at               timestamptz NOT NULL DEFAULT now(),
  CHECK (end_at > start_at),
  CHECK (total_paise = parking_paise + service_fee_paise + gst_paise),
  CHECK (host_earnings_paise <= parking_paise),
  CHECK (status <> 'PENDING_PAYMENT' OR hold_expires_at IS NOT NULL),
  CHECK (status NOT IN ('CONFIRMED', 'CHECKED_IN', 'COMPLETED') OR (vehicle_registration IS NOT NULL AND confirmed_at IS NOT NULL)),
  CHECK (status <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL)),
  CHECK (declined_by_booking_id IS NULL OR status = 'DECLINED')
);
-- Availability: overlapping bookings that hold a bay. Used with listing_peak_occupancy() below.
CREATE INDEX bookings_listing_time ON bookings (listing_id, start_at, end_at)
  WHERE status IN ('PENDING_HOST_APPROVAL', 'PENDING_PAYMENT', 'CONFIRMED', 'CHECKED_IN');
CREATE INDEX bookings_driver ON bookings (driver_id, start_at DESC);
CREATE INDEX bookings_host ON bookings (host_id, start_at DESC);
-- Job that expires unpaid holds.
CREATE INDEX bookings_holds ON bookings (hold_expires_at) WHERE status = 'PENDING_PAYMENT';
CREATE TRIGGER bookings_updated_at BEFORE UPDATE ON bookings FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- The most bays in use at any moment between p_start and p_end by bookings that hold a bay.
-- (PENDING_HOST_APPROVAL requests do not hold a bay: several can wait for the same times.)
-- The API locks the listing row (SELECT ... FOR UPDATE), checks peak < bays, then inserts, all in one transaction.
CREATE FUNCTION listing_peak_occupancy(p_listing_id text, p_start timestamptz, p_end timestamptz, p_ignore_booking_id text DEFAULT NULL)
RETURNS integer
LANGUAGE sql STABLE AS $$
  SELECT COALESCE(max(in_use), 0)::integer
  FROM (
    SELECT sum(delta) OVER (ORDER BY at, delta) AS in_use  -- at equal times, ends (-1) count before starts (+1)
    FROM (
      SELECT greatest(start_at, p_start) AS at, 1 AS delta
      FROM bookings
      WHERE listing_id = p_listing_id
        AND status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CHECKED_IN')
        AND start_at < p_end AND end_at > p_start
        AND id IS DISTINCT FROM p_ignore_booking_id
      UNION ALL
      SELECT least(end_at, p_end), -1
      FROM bookings
      WHERE listing_id = p_listing_id
        AND status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CHECKED_IN')
        AND start_at < p_end AND end_at > p_start
        AND id IS DISTINCT FROM p_ignore_booking_id
    ) AS events
  ) AS running;
$$;

-- Money in from drivers, through the payment gateway (Razorpay or similar). One booking can have several attempts.
CREATE TABLE payments (
  id                   text PRIMARY KEY CHECK (id LIKE 'pay\_%'),
  booking_id           text NOT NULL REFERENCES bookings (id),
  provider             text NOT NULL DEFAULT 'RAZORPAY',
  provider_order_id    text NOT NULL UNIQUE,
  provider_payment_id  text UNIQUE,
  amount_paise         bigint NOT NULL CHECK (amount_paise > 0),
  method               text CHECK (method IN ('UPI', 'CARD', 'NETBANKING')),
  status               text NOT NULL DEFAULT 'CREATED' CHECK (status IN ('CREATED', 'CAPTURED', 'FAILED')),
  failure_reason       text,
  provider_payload     jsonb,  -- the verified webhook / API response, for support
  created_at           timestamptz NOT NULL DEFAULT now(),
  captured_at          timestamptz,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  CHECK (status <> 'CAPTURED' OR (provider_payment_id IS NOT NULL AND captured_at IS NOT NULL))
);
CREATE INDEX payments_booking ON payments (booking_id);
-- At most one successful payment per booking.
CREATE UNIQUE INDEX payments_one_captured ON payments (booking_id) WHERE status = 'CAPTURED';
CREATE TRIGGER payments_updated_at BEFORE UPDATE ON payments FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Money back to drivers (cancellations, host declines after payment, ParkNest cancellations).
CREATE TABLE refunds (
  id                  text PRIMARY KEY CHECK (id LIKE 'rf\_%'),
  payment_id          text NOT NULL REFERENCES payments (id),
  booking_id          text NOT NULL REFERENCES bookings (id),
  amount_paise        bigint NOT NULL CHECK (amount_paise > 0),
  reason              text NOT NULL,
  initiated_by        text NOT NULL CHECK (initiated_by IN ('DRIVER', 'HOST', 'ADMIN', 'SYSTEM')),
  status              text NOT NULL DEFAULT 'PROCESSING' CHECK (status IN ('PROCESSING', 'REFUNDED', 'FAILED')),
  provider_refund_id  text UNIQUE,
  failure_reason      text,
  attempts            smallint NOT NULL DEFAULT 1 CHECK (attempts >= 1),
  created_at          timestamptz NOT NULL DEFAULT now(),
  completed_at        timestamptz,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  CHECK (status <> 'FAILED' OR failure_reason IS NOT NULL),
  CHECK (status <> 'REFUNDED' OR completed_at IS NOT NULL)
);
CREATE INDEX refunds_booking ON refunds (booking_id);
CREATE INDEX refunds_open ON refunds (created_at) WHERE status <> 'REFUNDED';
CREATE TRIGGER refunds_updated_at BEFORE UPDATE ON refunds FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Money out to hosts. A payout groups the host's share of completed bookings (payout_items).
CREATE TABLE payouts (
  id                  text PRIMARY KEY CHECK (id LIKE 'po\_%'),
  host_id             text NOT NULL REFERENCES users (id),
  payout_account_id   text NOT NULL REFERENCES payout_accounts (id),
  amount_paise        bigint NOT NULL CHECK (amount_paise > 0),
  status              text NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'PROCESSING', 'PAID', 'FAILED')),
  scheduled_for       date NOT NULL,
  provider_payout_id  text UNIQUE,
  utr                 text,  -- bank reference shown to the host once paid
  failure_reason      text,
  attempts            smallint NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  initiated_at        timestamptz,
  paid_at             timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  CHECK (status <> 'PAID' OR paid_at IS NOT NULL),
  CHECK (status <> 'FAILED' OR failure_reason IS NOT NULL)
);
CREATE INDEX payouts_host ON payouts (host_id, created_at DESC);
CREATE INDEX payouts_due ON payouts (scheduled_for) WHERE status IN ('SCHEDULED', 'FAILED');
CREATE TRIGGER payouts_updated_at BEFORE UPDATE ON payouts FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Which bookings a payout covers. Each booking is paid out once.
CREATE TABLE payout_items (
  payout_id     text NOT NULL REFERENCES payouts (id) ON DELETE CASCADE,
  booking_id    text NOT NULL UNIQUE REFERENCES bookings (id),
  amount_paise  bigint NOT NULL CHECK (amount_paise > 0),
  PRIMARY KEY (payout_id, booking_id)
);

-- =====================================================================================================================
-- Admin
-- =====================================================================================================================

-- Every admin action, with the reason given (the Activity screen). Rows are never updated or deleted.
CREATE TABLE admin_actions (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor_id     text NOT NULL REFERENCES users (id),
  action       text NOT NULL CHECK (action IN ('LISTING_APPROVED', 'LISTING_REJECTED', 'USER_BLOCKED', 'USER_UNBLOCKED',
                                               'BOOKING_CANCELLED', 'REFUND_RETRIED', 'PAYOUT_RETRIED')),
  target_type  text NOT NULL CHECK (target_type IN ('LISTING', 'USER', 'BOOKING', 'REFUND', 'PAYOUT')),
  target_id    text NOT NULL,
  reason       text,
  metadata     jsonb NOT NULL DEFAULT '{}',
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX admin_actions_recent ON admin_actions (created_at DESC);
CREATE INDEX admin_actions_target ON admin_actions (target_type, target_id);

