package com.parknest.api.common;

import java.util.regex.Pattern;

/** Indian mobile numbers in E.164: "+919876543210". */
public final class Phones {

    public static final Pattern E164_INDIA = Pattern.compile("^\\+91[6-9][0-9]{9}$");

    private Phones() {}

    public static boolean valid(String phone) {
        return phone != null && E164_INDIA.matcher(phone).matches();
    }

    /** "+919876543210" -> "+91 98••• ••210" */
    public static String mask(String phone) {
        return "+91 " + phone.substring(3, 5) + "••• ••" + phone.substring(phone.length() - 3);
    }
}
