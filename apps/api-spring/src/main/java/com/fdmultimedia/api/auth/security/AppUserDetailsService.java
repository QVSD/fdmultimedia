package com.fdmultimedia.api.auth.security;

import com.fdmultimedia.api.users.AppUserRepository;
import com.fdmultimedia.api.users.EmailNormalizer;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;
    private final EmailNormalizer emailNormalizer;

    public AppUserDetailsService(AppUserRepository users, EmailNormalizer emailNormalizer) {
        this.users = users;
        this.emailNormalizer = emailNormalizer;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String email = emailNormalizer.normalize(username);
        return users.findByEmailIgnoreCase(email)
                .map(AuthenticatedUser::new)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}
