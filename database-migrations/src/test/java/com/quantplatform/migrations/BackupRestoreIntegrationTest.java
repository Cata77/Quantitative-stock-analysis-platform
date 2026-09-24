package com.quantplatform.migrations;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.*;

@Testcontainers
class BackupRestoreIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb:2.29.1-pg18").asCompatibleSubstituteFor("postgres"));
    @Test void restoresHypertablesLineageOwnershipAndFlywayIntoANewDatabase() throws Exception {
        try(var c=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var s=c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE");
        }
        var flyway=Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).defaultSchema("operations").schemas("operations").load();
        flyway.migrate();
        try(var c=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var s=c.createStatement()) {
            s.execute("INSERT INTO public.tick_data(time,symbol,bid,ask) VALUES('2026-01-02T15:00Z','RESTORE',100,101)");
            s.execute("INSERT INTO reference.issuers(legal_name) VALUES('Restore lineage fixture')");
        }
        for(String script:new String[]{"backup.sh","restore.sh","provision-role.sql"})
            DB.copyFileToContainer(MountableFile.forHostPath(Path.of("../infrastructure/backup",script).toAbsolutePath()),"/tmp/"+script);
        run("psql","-X","-v","ON_ERROR_STOP=1","-f","/tmp/provision-role.sql");
        try(var c=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var s=c.createStatement()) {
            s.execute("ALTER ROLE quant_backup LOGIN PASSWORD 'isolated-backup-password'");
        }
        var backup=DB.execInContainer("env","PGHOST=localhost","PGDATABASE="+DB.getDatabaseName(),
            "PGUSER=quant_backup","PGPASSWORD=isolated-backup-password","sh","/tmp/backup.sh","/tmp/phase11");
        assertThat(backup.getExitCode()).as(backup.getStderr()).isZero();
        run("sh","/tmp/restore.sh","/tmp/phase11.dump","restored_fixture");
        var again=DB.execInContainer("env","PGHOST=localhost","PGUSER="+DB.getUsername(),"PGPASSWORD="+DB.getPassword(),"sh","/tmp/restore.sh","/tmp/phase11.dump","restored_fixture");
        assertThat(again.getExitCode()).isNotZero(); // Cannot overwrite a populated target.
        String url=DB.getJdbcUrl().replace("/"+DB.getDatabaseName(),"/restored_fixture");
        var restored=Flyway.configure().dataSource(url,DB.getUsername(),DB.getPassword()).defaultSchema("operations").schemas("operations").load();
        restored.validate();assertThat(restored.migrate().migrationsExecuted).isZero();
        try(var c=DriverManager.getConnection(url,DB.getUsername(),DB.getPassword());var s=c.createStatement()) {
            try(var rows=s.executeQuery("SELECT count(*) FROM public.tick_data WHERE symbol='RESTORE'")) { rows.next();assertThat(rows.getInt(1)).isEqualTo(1); }
            try(var rows=s.executeQuery("SELECT count(*) FROM reference.issuers WHERE legal_name='Restore lineage fixture'")) { rows.next();assertThat(rows.getInt(1)).isEqualTo(1); }
            try(var rows=s.executeQuery("SELECT count(*) FROM timescaledb_information.hypertables")) { rows.next();assertThat(rows.getInt(1)).isEqualTo(5); }
            s.execute("SET ROLE quant_research");
            assertThatThrownBy(()->s.execute("SELECT * FROM public.users")).isInstanceOf(SQLException.class);
            s.execute("SELECT * FROM research.model_versions");
        }
    }
    void run(String... command) throws Exception {
        var args=new java.util.ArrayList<>(java.util.List.of("env","PGHOST=localhost","PGDATABASE="+DB.getDatabaseName(),"PGUSER="+DB.getUsername(),"PGPASSWORD="+DB.getPassword()));
        args.addAll(java.util.List.of(command));var result=DB.execInContainer(args.toArray(String[]::new));
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }
}
