package com.parknest.api.hosting;

import com.parknest.api.common.ApiException;
import com.parknest.api.common.Ids;
import com.parknest.api.hosting.HostDtos.PayoutAccountView;
import com.parknest.api.hosting.HostDtos.PayoutRequest;
import java.util.Optional;
import java.util.regex.Pattern;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where hosts are paid. A change adds a new row and retires the old one (past payouts keep their account).
 * New accounts are VERIFYING until the ₹1 test transfer (payment provider) confirms them.
 */
@Service
public class PayoutAccounts {

    private static final Pattern UPI = Pattern.compile("^[A-Za-z0-9._-]{2,200}@[A-Za-z][A-Za-z0-9.]{1,64}$");
    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private static final Pattern ACCOUNT = Pattern.compile("^\\d{9,18}$");

    private final PayoutAccountRepository accounts;
    private final PayoutCrypto crypto;

    public PayoutAccounts(PayoutAccountRepository accounts, PayoutCrypto crypto) {
        this.accounts = accounts;
        this.crypto = crypto;
    }

    public Optional<PayoutAccountView> current(String userId) {
        return accounts.findByUserIdAndReplacedAtIsNull(userId).map(PayoutAccounts::view);
    }

    static PayoutAccountView view(PayoutAccount a) {
        return new PayoutAccountView(a.getMethod(), a.getDisplay(), a.getAccountHolderName(), a.getStatus());
    }

    /** Checks and saves a new payout account. fieldPrefix is "payout." inside the onboarding body. */
    @Transactional
    public PayoutAccountView replace(String userId, String defaultName, PayoutRequest req, String fieldPrefix) {
        if (req == null || req.method() == null) throw ApiException.invalid(fieldPrefix + "method", "Choose UPI or bank account");
        PayoutAccount account = new PayoutAccount();
        account.setId(Ids.newId("pa"));
        account.setUserId(userId);
        if ("UPI".equals(req.method())) {
            String upi = req.upiId() == null ? "" : req.upiId().trim();
            if (!UPI.matcher(upi).matches()) throw ApiException.invalid(fieldPrefix + "upiId", "Enter a valid UPI ID, e.g. name@okaxis");
            account.setMethod("UPI");
            account.setUpiId(upi);
            account.setAccountHolderName(defaultName);
            account.setDisplay(maskUpi(upi));
        } else if ("BANK_ACCOUNT".equals(req.method())) {
            String name = req.accountHolderName() == null ? "" : req.accountHolderName().trim();
            String number = req.accountNumber() == null ? "" : req.accountNumber().replaceAll("\\s", "");
            String ifsc = req.ifsc() == null ? "" : req.ifsc().trim().toUpperCase();
            if (name.length() < 2) throw ApiException.invalid(fieldPrefix + "accountHolderName", "Enter the name on the bank account");
            if (!ACCOUNT.matcher(number).matches()) throw ApiException.invalid(fieldPrefix + "accountNumber", "Enter 9 to 18 digits");
            if (!IFSC.matcher(ifsc).matches()) throw ApiException.invalid(fieldPrefix + "ifsc", "Enter a valid IFSC code, e.g. HDFC0001234");
            String last4 = number.substring(number.length() - 4);
            account.setMethod("BANK_ACCOUNT");
            account.setAccountHolderName(name);
            account.setAccountNumberEncrypted(crypto.encrypt(number));
            account.setAccountNumberLast4(last4);
            account.setIfsc(ifsc);
            account.setDisplay(ifsc.substring(0, 4) + " ••••" + last4);
        } else {
            throw ApiException.invalid(fieldPrefix + "method", "Choose UPI or bank account");
        }
        accounts.retireCurrent(userId, OffsetDateTime.now());
        return view(accounts.saveAndFlush(account));
    }

    /** "venkatesh@okaxis" -> "ve••••sh@okaxis" */
    static String maskUpi(String upi) {
        int at = upi.indexOf('@');
        String name = upi.substring(0, at);
        String shown = name.length() <= 4 ? name.charAt(0) + "••••" : name.substring(0, 2) + "••••" + name.substring(name.length() - 2);
        return shown + upi.substring(at);
    }
}
