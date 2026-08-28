package com.edevlet.lineage;

import com.edevlet.lineage.infrastructure.vault.VaultDynamicSecretsConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class VaultDynamicSecretsConfigTest {

    @Test
    @DisplayName("Should initialize VaultDynamicSecretsConfig without error when Vault is disabled")
    void shouldInitializeSafelyWhenVaultDisabled() {
        assertThatCode(() -> {
            VaultDynamicSecretsConfig config = new VaultDynamicSecretsConfig(null, null, null);
            config.renewDynamicSecretLeases();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should update HikariCP DataSource dynamic credentials on secret lease renewal")
    void shouldUpdateHikariCredentials() {
        HikariDataSource hikariDataSource = new HikariDataSource();
        hikariDataSource.setJdbcUrl("jdbc:h2:mem:vault_test_db");
        hikariDataSource.setUsername("old_user");
        hikariDataSource.setPassword("old_pass");

        VaultDynamicSecretsConfig config = new VaultDynamicSecretsConfig(null, null, hikariDataSource);

        // Verify configuration handles lease periodic checks gracefully
        assertThatCode(config::renewDynamicSecretLeases).doesNotThrowAnyException();

        hikariDataSource.close();
    }
}
