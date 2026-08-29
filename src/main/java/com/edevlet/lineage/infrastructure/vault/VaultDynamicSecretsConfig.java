package com.edevlet.lineage.infrastructure.vault;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.core.lease.SecretLeaseContainer;
import org.springframework.vault.core.lease.domain.Lease;
import org.springframework.vault.core.lease.event.SecretLeaseCreatedEvent;
import org.springframework.vault.core.lease.event.SecretLeaseExpiredEvent;
import org.springframework.vault.support.VaultResponse;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Vault Dynamic Secrets Injection & 1-Hour Credentials Lease Renewal Manager.
 * Injects PostgreSQL database ('database/creds/pedigree-db-role') dynamic credentials via
 * Spring Cloud Vault. Automatically renews leases and updates the connection pool prior to
 * 1-hour expiration.
 */
@Slf4j
@Configuration
@EnableScheduling
public class VaultDynamicSecretsConfig {

    private final VaultOperations vaultOperations;
    private final SecretLeaseContainer leaseContainer;
    private final DataSource dataSource;

    @Value("${app.security.vault.enabled:false}")
    private boolean vaultEnabled;

    @Value("${spring.cloud.vault.database.role:pedigree-db-role}")
    private String databaseRole;

    @Autowired
    public VaultDynamicSecretsConfig(
            @Autowired(required = false) VaultOperations vaultOperations,
            @Autowired(required = false) SecretLeaseContainer leaseContainer,
            @Autowired(required = false) DataSource dataSource) {
        this.vaultOperations = vaultOperations;
        this.leaseContainer = leaseContainer;
        this.dataSource = dataSource;

        if (leaseContainer != null) {
            configureLeaseContainerListeners(leaseContainer);
        }
    }

    private void configureLeaseContainerListeners(SecretLeaseContainer container) {
        container.addLeaseListener(event -> {
            Lease lease = event.getLease();
            String path = event.getSource().getPath();

            if (event instanceof SecretLeaseCreatedEvent createdEvent) {
                log.info("Vault Secret Lease CREATED for path: {}, leaseId: {}, duration: {}s (Expires in 1h)",
                        path, lease.getLeaseId(), lease.getLeaseDuration());
                updateDynamicCredentials(path, createdEvent.getSecrets());
            } else if (event instanceof SecretLeaseExpiredEvent) {
                log.warn("Vault Secret Lease EXPIRED for path: {}, triggering immediate dynamic re-issuance", path);
                refreshDynamicSecretPath(path);
            }
        });
    }

    /**
     * Scheduled heartbeat task executing every 30 minutes to guarantee credential leases with 1-hour TTL
     * are proactively renewed or rotated smoothly without connection disruption.
     */
    @Scheduled(fixedRate = 1800000) // Every 30 minutes
    public void renewDynamicSecretLeases() {
        if (!vaultEnabled || vaultOperations == null) {
            log.debug("Vault dynamic secret lease renewal skipped (Vault disabled or not configured)");
            return;
        }

        log.info("Executing scheduled Vault dynamic credentials lease check (1-hour TTL policy enforcement)");
        try {
            // Fetch fresh 1-hour dynamic database credentials from Vault
            VaultResponse dbSecret = vaultOperations.read("database/creds/" + databaseRole);
            if (dbSecret != null && dbSecret.getData() != null) {
                updateDynamicCredentials("database/creds/" + databaseRole, dbSecret.getData());
            }
        } catch (Exception e) {
            log.warn("Vault dynamic secret fetch failed during scheduled check: {}", e.getMessage());
        }
    }

    private void updateDynamicCredentials(String path, Map<String, Object> secrets) {
        if (secrets == null || !path.contains("database")) {
            return;
        }

        String username = (String) secrets.get("username");
        String password = (String) secrets.get("password");
        if (username == null || password == null || !(dataSource instanceof HikariDataSource hikariDataSource)) {
            return;
        }

        log.info("Dynamically rotating PostgreSQL DataSource credentials from Vault (user: {})", username);
        hikariDataSource.setUsername(username);
        hikariDataSource.setPassword(password);

        // setUsername/setPassword alone only affect NEW connections the pool opens.
        // Vault's dynamic database secrets engine revokes the outgoing role's DB user at
        // lease end, not just rotates it, so already-open pooled connections need to be
        // proactively cycled out.
        if (hikariDataSource.isRunning()) {
            hikariDataSource.getHikariPoolMXBean().softEvictConnections();
        }
    }

    private void refreshDynamicSecretPath(String path) {
        if (vaultOperations == null) {
            return;
        }
        try {
            VaultResponse secret = vaultOperations.read(path);
            if (secret != null && secret.getData() != null) {
                updateDynamicCredentials(path, secret.getData());
            }
        } catch (Exception e) {
            log.error("Failed to re-issue dynamic secrets for path {}: {}", path, e.getMessage());
        }
    }
}
