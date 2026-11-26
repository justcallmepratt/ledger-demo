package dev.justcallmepratt.ledger.persistence;

import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.AccountFlags;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountRepository {

    private static final String COLUMNS = "id, ledger, code, name, debits_must_not_exceed_credits, "
            + "credits_must_not_exceed_debits, history, closed, debits_pending, debits_posted, "
            + "credits_pending, credits_posted, created_at";

    private static final RowMapper<Account> MAPPER = (rs, n) -> new Account(
            rs.getObject("id", UUID.class),
            rs.getInt("ledger"),
            rs.getInt("code"),
            rs.getString("name"),
            new AccountFlags(rs.getBoolean("debits_must_not_exceed_credits"),
                    rs.getBoolean("credits_must_not_exceed_debits"),
                    rs.getBoolean("history"),
                    rs.getBoolean("closed")),
            rs.getLong("debits_pending"),
            rs.getLong("debits_posted"),
            rs.getLong("credits_pending"),
            rs.getLong("credits_posted"),
            Db.instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /**
     * Inserts the account unless the id exists. Returns false if it already existed, which
     * (with ON CONFLICT) also covers a concurrent insert that committed first: no aborted
     * transaction, the caller just re-reads and compares.
     */
    public boolean insertIfAbsent(Account a) {
        int rows = jdbc.sql("INSERT INTO accounts (id, ledger, code, name, debits_must_not_exceed_credits, "
                        + "credits_must_not_exceed_debits, history, closed, debits_pending, debits_posted, "
                        + "credits_pending, credits_posted, created_at) "
                        + "VALUES (:id, :ledger, :code, :name, :dmnec, :cmned, :history, :closed, "
                        + ":dpend, :dposted, :cpend, :cposted, :createdAt) ON CONFLICT (id) DO NOTHING")
                .param("id", a.id())
                .param("ledger", a.ledger())
                .param("code", a.code())
                .param("name", a.name())
                .param("dmnec", a.flags().debitsMustNotExceedCredits())
                .param("cmned", a.flags().creditsMustNotExceedDebits())
                .param("history", a.flags().history())
                .param("closed", a.flags().closed())
                .param("dpend", a.debitsPending())
                .param("dposted", a.debitsPosted())
                .param("cpend", a.creditsPending())
                .param("cposted", a.creditsPosted())
                .param("createdAt", Db.ts(a.createdAt()))
                .update();
        return rows == 1;
    }

    /**
     * Locks the given accounts with {@code SELECT ... FOR UPDATE ... ORDER BY id}. Every
     * writer uses this single method, so locks are always taken in ascending id order and
     * cannot deadlock. Missing ids are simply absent from the result.
     */
    public Map<UUID, Account> lockForUpdate(Collection<UUID> ids) {
        Map<UUID, Account> result = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        List<Object> params = new ArrayList<>(ids.size());
        StringBuilder placeholders = new StringBuilder();
        for (UUID id : ids) {
            if (!params.isEmpty()) {
                placeholders.append(',');
            }
            placeholders.append('?');
            params.add(id);
        }
        List<Account> rows = jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id IN (" + placeholders
                        + ") ORDER BY id FOR UPDATE")
                .params(params)
                .query(MAPPER)
                .list();
        for (Account row : rows) {
            result.put(row.id(), row);
        }
        return result;
    }

    public void updateCounters(Account a) {
        jdbc.sql("UPDATE accounts SET debits_pending = :dpend, debits_posted = :dposted, credits_pending = :cpend, "
                        + "credits_posted = :cposted WHERE id = :id")
                .param("dpend", a.debitsPending())
                .param("dposted", a.debitsPosted())
                .param("cpend", a.creditsPending())
                .param("cposted", a.creditsPosted())
                .param("id", a.id())
                .update();
    }

    /** Newest first by (created_at, id). Null cursor = first page. */
    public List<Account> page(Instant beforeCreatedAt, UUID beforeId, int limit) {
        if (beforeCreatedAt == null) {
            return jdbc.sql("SELECT " + COLUMNS + " FROM accounts ORDER BY created_at DESC, id DESC LIMIT :limit")
                    .param("limit", limit)
                    .query(MAPPER)
                    .list();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE (created_at, id) < (:ts, :id) "
                        + "ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("ts", Db.ts(beforeCreatedAt))
                .param("id", beforeId)
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }
}
