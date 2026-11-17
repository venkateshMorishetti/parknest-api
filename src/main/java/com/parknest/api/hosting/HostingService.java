package com.parknest.api.hosting;

import com.parknest.api.common.ApiException;
import com.parknest.api.common.Times;
import com.parknest.api.config.AppProperties;
import com.parknest.api.hosting.HostDtos.Channels;
import com.parknest.api.hosting.HostDtos.HostSettings;
import com.parknest.api.hosting.HostDtos.NotificationSettings;
import com.parknest.api.hosting.HostDtos.Onboarding;
import com.parknest.api.hosting.HostDtos.PayoutAccountView;
import com.parknest.api.users.User;
import com.parknest.api.users.UserService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turning hosting on (/host/start), the payout account and host settings. No documents and no review of the person:
 * a verified phone, a payout account and the accepted terms are enough. Spots are reviewed one by one instead.
 */
@Service
public class HostingService {

    private final HostProfileRepository profiles;
    private final PayoutAccounts payouts;
    private final NotificationPreferencesRepository notifications;
    private final UserService users;
    private final AppProperties props;

    public HostingService(HostProfileRepository profiles, PayoutAccounts payouts, NotificationPreferencesRepository notifications,
                          UserService users, AppProperties props) {
        this.profiles = profiles;
        this.payouts = payouts;
        this.notifications = notifications;
        this.users = users;
        this.props = props;
    }

    /** Throws 403 HOSTING_NOT_STARTED unless hosting is on. */
    public HostProfile requireHost(String userId) {
        return profiles.findById(userId).filter(HostProfile::isActive)
                .orElseThrow(() -> ApiException.forbidden("HOSTING_NOT_STARTED", "Add a payout account to start hosting"));
    }

    @Transactional(readOnly = true)
    public Onboarding onboarding(String userId) {
        User user = users.find(userId);
        HostProfile profile = profiles.findById(userId).orElse(new HostProfile(userId));
        return view(user, profile);
    }

    /** POST /host/onboarding: saves the payout account and turns hosting on straight away. */
    @Transactional
    public Onboarding start(String userId, HostDtos.OnboardingRequest req) {
        User user = users.find(userId);
        HostProfile profile = profiles.findById(userId).orElse(new HostProfile(userId));
        if (profile.isActive()) throw ApiException.conflict("ALREADY_HOSTING", null, "Hosting is already on for your account");
        if (!user.isPhoneVerified()) throw ApiException.forbidden("PHONE_NOT_VERIFIED", "Verify your mobile number first");
        if (!Boolean.TRUE.equals(req.acceptedHostTerms())) throw ApiException.invalid("acceptedHostTerms", "Agree to the hosting terms to continue");
        payouts.replace(userId, user.getName(), req.payout(), "payout.");
        OffsetDateTime now = OffsetDateTime.now();
        profile.setStatus("ACTIVE");
        profile.setTermsVersion(props.hosting().termsVersion());
        profile.setTermsAcceptedAt(now);
        profile.setActivatedAt(now);
        profiles.saveAndFlush(profile);
        return view(user, profile);
    }

    /** PUT /host/payout-account */
    @Transactional
    public PayoutAccountView updatePayout(String userId, HostDtos.PayoutRequest req) {
        requireHost(userId);
        return payouts.replace(userId, users.find(userId).getName(), req, "");
    }

    /** GET /host/settings */
    @Transactional(readOnly = true)
    public HostSettings settings(String userId) {
        HostProfile profile = requireHost(userId);
        User user = users.find(userId);
        return new HostSettings(
                new HostDtos.SettingsProfile(user.getName(), user.getPhone(), user.getEmail(), Times.ist(profile.getActivatedAt())),
                payouts.current(userId).orElse(null),
                toDto(notifications.findById(userId).map(NotificationPreferences::getSettings).orElse(defaults())));
    }

    /** PUT /host/settings/notifications */
    @Transactional
    public NotificationSettings updateNotifications(String userId, NotificationSettings req) {
        requireHost(userId);
        Map<String, Map<String, Boolean>> settings = new LinkedHashMap<>();
        settings.put("newRequests", channels(req.newRequests()));
        settings.put("bookingUpdates", channels(req.bookingUpdates()));
        settings.put("payouts", channels(req.payouts()));
        settings.put("tips", channels(req.tips()));
        NotificationPreferences prefs = notifications.findById(userId).orElse(new NotificationPreferences(userId, settings));
        prefs.setSettings(settings);
        notifications.saveAndFlush(prefs);
        return toDto(settings);
    }

    private Onboarding view(User user, HostProfile profile) {
        return new Onboarding(profile.getStatus(), profile.isActive(), user.getPhone(), user.isPhoneVerified(),
                profile.isActive() ? payouts.current(user.getId()).orElse(null) : null, Times.ist(profile.getActivatedAt()));
    }

    /** New hosts: SMS and email for requests and booking changes, email for payouts, no tips. */
    static Map<String, Map<String, Boolean>> defaults() {
        Map<String, Map<String, Boolean>> d = new LinkedHashMap<>();
        d.put("newRequests", Map.of("sms", true, "email", true));
        d.put("bookingUpdates", Map.of("sms", true, "email", true));
        d.put("payouts", Map.of("sms", false, "email", true));
        d.put("tips", Map.of("sms", false, "email", false));
        return d;
    }

    private static Map<String, Boolean> channels(Channels c) {
        return Map.of("sms", c.sms(), "email", c.email());
    }

    private static NotificationSettings toDto(Map<String, Map<String, Boolean>> s) {
        Map<String, Map<String, Boolean>> d = defaults();
        java.util.function.Function<String, Channels> get = key -> {
            Map<String, Boolean> c = s.getOrDefault(key, d.get(key));
            return new Channels(Boolean.TRUE.equals(c.get("sms")), Boolean.TRUE.equals(c.get("email")));
        };
        return new NotificationSettings(get.apply("newRequests"), get.apply("bookingUpdates"), get.apply("payouts"), get.apply("tips"));
    }

}
