package io.mszymanski.orknux.server.database

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import javax.sql.DataSource

/**
 * The connection pool is the operator's to size, through the variables
 * DOCKERHUB.md names. A setting written into application.yml under a path
 * Spring does not bind is a setting that silently does nothing, so this reads
 * the pool that was actually built. Issue #616.
 */
@SpringBootTest(
    properties = [
        "ORKNUX_DB_POOL_SIZE=7",
        // The build pins the suite's pool at 4 (app/pom.xml), which outranks the
        // variable; put the variable back in charge the way application.yml does.
        "spring.datasource.hikari.maximum-pool-size=\${ORKNUX_DB_POOL_SIZE:10}",
        "ORKNUX_DB_POOL_WAIT_MS=12000",
        "ORKNUX_DB_POOL_LEAK_MS=45000",
    ],
)
class ConnectionPoolSettingsTest(@Autowired val dataSource: DataSource) {

    @Test
    fun `the pool is built from the ORKNUX_DB_POOL variables`() {
        val pool = dataSource.unwrap(HikariDataSource::class.java)
        assertThat(pool.maximumPoolSize).isEqualTo(7)
        assertThat(pool.connectionTimeout).isEqualTo(12_000)
        assertThat(pool.leakDetectionThreshold).isEqualTo(45_000)
    }
}
