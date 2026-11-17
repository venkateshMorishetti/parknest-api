package com.parknest.api.auth;

import com.parknest.api.common.Phones;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Used until a real SMS provider is added: writes the code to the log instead of sending it. A real provider is a
 * second SmsSender bean marked @Primary. Production must not go live with this one.
 */
@Component
public class LogSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LogSmsSender.class);

    @Override
    public void sendOtp(String phone, String code) {
        log.info("OTP for {} is {} (SMS not sent: no SMS provider configured)", Phones.mask(phone), code);
    }
}
