package io.joblab.scheduler.security;

import io.joblab.scheduler.security.IdentityStore.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
class BootstrapAccounts implements ApplicationRunner {

    private static final UUID ACME = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID GLOBEX = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final IdentityStore accounts;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transaction;
    private final boolean demo;
    private final String bootstrapPassword;

    BootstrapAccounts(IdentityStore accounts, PasswordEncoder passwords, TransactionTemplate transaction,
                      @Value("${joblab.security.demo-accounts}") boolean demo,
                      @Value("${joblab.security.bootstrap-password}") String bootstrapPassword) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.transaction = transaction;
        this.demo = demo;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (demo) {
            String password = "JobLab-Demo-2026!";
            String adminHash = passwords.encode(password);
            String operatorHash = passwords.encode(password);
            String viewerHash = passwords.encode(password);
            String globexHash = passwords.encode(password);
            transaction.executeWithoutResult(status -> {
                accounts.seedTenant(GLOBEX, "globex", 2);
                accounts.seedAccount(ACME, "admin", adminHash, Role.ADMIN);
                accounts.seedAccount(ACME, "operator", operatorHash, Role.OPERATOR);
                accounts.seedAccount(ACME, "viewer", viewerHash, Role.VIEWER);
                accounts.seedAccount(GLOBEX, "admin", globexHash, Role.ADMIN);
            });
        } else if (!bootstrapPassword.isEmpty()) {
            if (bootstrapPassword.length() < 16 || bootstrapPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new IllegalArgumentException("Bootstrap password must be at least 16 characters and at most 72 UTF-8 bytes");
            }
            String hash = passwords.encode(bootstrapPassword);
            transaction.executeWithoutResult(status -> accounts.seedAccount(ACME, "admin", hash, Role.ADMIN));
        }
    }
}
