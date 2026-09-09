package com.fdmultimedia.api.users;

import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class EmailNormalizer {

    public String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
