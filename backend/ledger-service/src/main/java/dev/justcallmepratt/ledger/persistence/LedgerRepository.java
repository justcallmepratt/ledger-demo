package dev.justcallmepratt.ledger.persistence;

import dev.justcallmepratt.ledger.domain.Ledger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class LedgerRepository {

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Ledger> findAll() {
        return jdbc.sql("SELECT id, currency, asset_scale FROM ledgers ORDER BY id")
                .query((rs, n) -> new Ledger(rs.getInt("id"), rs.getString("currency").trim(),
                        rs.getInt("asset_scale")))
                .list();
    }
}
